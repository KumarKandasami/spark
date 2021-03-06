/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.scheduler

import java.util.Properties

import scala.collection.Map
import scala.collection.mutable

import org.apache.spark.{Logging, TaskEndReason}
import org.apache.spark.executor.TaskMetrics
import org.apache.spark.storage.BlockManagerId
import org.apache.spark.util.{Distribution, Utils}

sealed trait SparkListenerEvent

case class SparkListenerStageSubmitted(stageInfo: StageInfo, properties: Properties = null)
  extends SparkListenerEvent

case class SparkListenerStageCompleted(stageInfo: StageInfo) extends SparkListenerEvent

case class SparkListenerTaskStart(stageId: Int, taskInfo: TaskInfo) extends SparkListenerEvent

case class SparkListenerTaskGettingResult(taskInfo: TaskInfo) extends SparkListenerEvent

case class SparkListenerTaskEnd(
    stageId: Int,
    taskType: String,
    reason: TaskEndReason,
    taskInfo: TaskInfo,
    taskMetrics: TaskMetrics)
  extends SparkListenerEvent

case class SparkListenerJobStart(jobId: Int, stageIds: Seq[Int], properties: Properties = null)
  extends SparkListenerEvent

case class SparkListenerJobEnd(jobId: Int, jobResult: JobResult) extends SparkListenerEvent

case class SparkListenerEnvironmentUpdate(environmentDetails: Map[String, Seq[(String, String)]])
  extends SparkListenerEvent

case class SparkListenerBlockManagerAdded(blockManagerId: BlockManagerId, maxMem: Long)
  extends SparkListenerEvent

case class SparkListenerBlockManagerRemoved(blockManagerId: BlockManagerId)
  extends SparkListenerEvent

case class SparkListenerUnpersistRDD(rddId: Int) extends SparkListenerEvent

/** An event used in the listener to shutdown the listener daemon thread. */
private[spark] case object SparkListenerShutdown extends SparkListenerEvent


/**
 * Interface for listening to events from the Spark scheduler.
 */
trait SparkListener {
  /**
   * Called when a stage completes successfully or fails, with information on the completed stage.
   */
  def onStageCompleted(stageCompleted: SparkListenerStageCompleted) { }

  /**
   * Called when a stage is submitted
   */
  def onStageSubmitted(stageSubmitted: SparkListenerStageSubmitted) { }

  /**
   * Called when a task starts
   */
  def onTaskStart(taskStart: SparkListenerTaskStart) { }

  /**
   * Called when a task begins remotely fetching its result (will not be called for tasks that do
   * not need to fetch the result remotely).
   */
  def onTaskGettingResult(taskGettingResult: SparkListenerTaskGettingResult) { }

  /**
   * Called when a task ends
   */
  def onTaskEnd(taskEnd: SparkListenerTaskEnd) { }

  /**
   * Called when a job starts
   */
  def onJobStart(jobStart: SparkListenerJobStart) { }

  /**
   * Called when a job ends
   */
  def onJobEnd(jobEnd: SparkListenerJobEnd) { }

  /**
   * Called when environment properties have been updated
   */
  def onEnvironmentUpdate(environmentUpdate: SparkListenerEnvironmentUpdate) { }

  /**
   * Called when a new block manager has joined
   */
  def onBlockManagerAdded(blockManagerAdded: SparkListenerBlockManagerAdded) { }

  /**
   * Called when an existing block manager has been removed
   */
  def onBlockManagerRemoved(blockManagerRemoved: SparkListenerBlockManagerRemoved) { }

  /**
   * Called when an RDD is manually unpersisted by the application
   */
  def onUnpersistRDD(unpersistRDD: SparkListenerUnpersistRDD) { }
}

/**
 * Simple SparkListener that logs a few summary statistics when each stage completes
 */
class StatsReportListener extends SparkListener with Logging {

  import org.apache.spark.scheduler.StatsReportListener._

  private val taskInfoMetrics = mutable.Buffer[(TaskInfo, TaskMetrics)]()

  override def onTaskEnd(taskEnd: SparkListenerTaskEnd) {
    val info = taskEnd.taskInfo
    val metrics = taskEnd.taskMetrics
    if (info != null && metrics != null) {
      taskInfoMetrics += ((info, metrics))
    }
  }

  override def onStageCompleted(stageCompleted: SparkListenerStageCompleted) {
    implicit val sc = stageCompleted
    this.logInfo("Finished stage: " + stageCompleted.stageInfo)
    showMillisDistribution("task runtime:", (info, _) => Some(info.duration), taskInfoMetrics)

    // Shuffle write
    showBytesDistribution("shuffle bytes written:",
      (_, metric) => metric.shuffleWriteMetrics.map(_.shuffleBytesWritten), taskInfoMetrics)

    // Fetch & I/O
    showMillisDistribution("fetch wait time:",
      (_, metric) => metric.shuffleReadMetrics.map(_.fetchWaitTime), taskInfoMetrics)
    showBytesDistribution("remote bytes read:",
      (_, metric) => metric.shuffleReadMetrics.map(_.remoteBytesRead), taskInfoMetrics)
    showBytesDistribution("task result size:",
      (_, metric) => Some(metric.resultSize), taskInfoMetrics)

    // Runtime breakdown
    val runtimePcts = taskInfoMetrics.map { case (info, metrics) =>
      RuntimePercentage(info.duration, metrics)
    }
    showDistribution("executor (non-fetch) time pct: ",
      Distribution(runtimePcts.map(_.executorPct * 100)), "%2.0f %%")
    showDistribution("fetch wait time pct: ",
      Distribution(runtimePcts.flatMap(_.fetchPct.map(_ * 100))), "%2.0f %%")
    showDistribution("other time pct: ", Distribution(runtimePcts.map(_.other * 100)), "%2.0f %%")
    taskInfoMetrics.clear()
  }

}

private[spark] object StatsReportListener extends Logging {

  // For profiling, the extremes are more interesting
  val percentiles = Array[Int](0,5,10,25,50,75,90,95,100)
  val probabilities = percentiles.map(_ / 100.0)
  val percentilesHeader = "\t" + percentiles.mkString("%\t") + "%"

  def extractDoubleDistribution(
      taskInfoMetrics: Seq[(TaskInfo, TaskMetrics)],
      getMetric: (TaskInfo, TaskMetrics) => Option[Double]): Option[Distribution] = {
    Distribution(taskInfoMetrics.flatMap { case (info, metric) => getMetric(info, metric) })
  }

  // Is there some way to setup the types that I can get rid of this completely?
  def extractLongDistribution(
      taskInfoMetrics: Seq[(TaskInfo, TaskMetrics)],
      getMetric: (TaskInfo, TaskMetrics) => Option[Long]): Option[Distribution] = {
    extractDoubleDistribution(
      taskInfoMetrics,
      (info, metric) => { getMetric(info, metric).map(_.toDouble) })
  }

  def showDistribution(heading: String, d: Distribution, formatNumber: Double => String) {
    val stats = d.statCounter
    val quantiles = d.getQuantiles(probabilities).map(formatNumber)
    logInfo(heading + stats)
    logInfo(percentilesHeader)
    logInfo("\t" + quantiles.mkString("\t"))
  }

  def showDistribution(
      heading: String,
      dOpt: Option[Distribution],
      formatNumber: Double => String) {
    dOpt.foreach { d => showDistribution(heading, d, formatNumber)}
  }

  def showDistribution(heading: String, dOpt: Option[Distribution], format:String) {
    def f(d: Double) = format.format(d)
    showDistribution(heading, dOpt, f _)
  }

  def showDistribution(
      heading: String,
      format: String,
      getMetric: (TaskInfo, TaskMetrics) => Option[Double],
      taskInfoMetrics: Seq[(TaskInfo, TaskMetrics)]) {
    showDistribution(heading, extractDoubleDistribution(taskInfoMetrics, getMetric), format)
  }

  def showBytesDistribution(
      heading:String,
      getMetric: (TaskInfo, TaskMetrics) => Option[Long],
      taskInfoMetrics: Seq[(TaskInfo, TaskMetrics)]) {
    showBytesDistribution(heading, extractLongDistribution(taskInfoMetrics, getMetric))
  }

  def showBytesDistribution(heading: String, dOpt: Option[Distribution]) {
    dOpt.foreach { dist => showBytesDistribution(heading, dist) }
  }

  def showBytesDistribution(heading: String, dist: Distribution) {
    showDistribution(heading, dist, (d => Utils.bytesToString(d.toLong)): Double => String)
  }

  def showMillisDistribution(heading: String, dOpt: Option[Distribution]) {
    showDistribution(heading, dOpt,
      (d => StatsReportListener.millisToString(d.toLong)): Double => String)
  }

  def showMillisDistribution(
      heading: String,
      getMetric: (TaskInfo, TaskMetrics) => Option[Long],
      taskInfoMetrics: Seq[(TaskInfo, TaskMetrics)]) {
    showMillisDistribution(heading, extractLongDistribution(taskInfoMetrics, getMetric))
  }

  val seconds = 1000L
  val minutes = seconds * 60
  val hours = minutes * 60

  /**
   * Reformat a time interval in milliseconds to a prettier format for output
   */
  def millisToString(ms: Long) = {
    val (size, units) =
      if (ms > hours) {
        (ms.toDouble / hours, "hours")
      } else if (ms > minutes) {
        (ms.toDouble / minutes, "min")
      } else if (ms > seconds) {
        (ms.toDouble / seconds, "s")
      } else {
        (ms.toDouble, "ms")
      }
    "%.1f %s".format(size, units)
  }
}

private case class RuntimePercentage(executorPct: Double, fetchPct: Option[Double], other: Double)

private object RuntimePercentage {
  def apply(totalTime: Long, metrics: TaskMetrics): RuntimePercentage = {
    val denom = totalTime.toDouble
    val fetchTime = metrics.shuffleReadMetrics.map(_.fetchWaitTime)
    val fetch = fetchTime.map(_ / denom)
    val exec = (metrics.executorRunTime - fetchTime.getOrElse(0L)) / denom
    val other = 1.0 - (exec + fetch.getOrElse(0d))
    RuntimePercentage(exec, fetch, other)
  }
}
