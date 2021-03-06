/**
 * Copyright 2011-2012 eBusiness Information, Groupe Excilys (www.excilys.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.excilys.ebi.gatling.charts.util

import scala.annotation.tailrec
import scala.collection.SortedMap
import scala.math.{ sqrt, round, pow, max }

import com.excilys.ebi.gatling.core.action.EndAction.END_OF_SCENARIO
import com.excilys.ebi.gatling.core.action.StartAction.START_OF_SCENARIO
import com.excilys.ebi.gatling.core.result.message.RequestStatus.{ RequestStatus, KO }
import com.excilys.ebi.gatling.core.result.reader.ChartRequestRecord

object StatisticsHelper {

	val NO_PLOT_MAGIC_VALUE = -1

	def meanTime(timeFunction: ChartRequestRecord => Long)(data: Seq[ChartRequestRecord]): Long = if (data.isEmpty) NO_PLOT_MAGIC_VALUE else (data.map(timeFunction(_)).sum / data.length.toDouble).toLong

	val meanResponseTime = meanTime(_.responseTime) _

	val meanLatency = meanTime(_.latency) _

	/**
	 * Compute the population standard deviation of the provided data.
	 *
	 * @param data is all the ChartRequestRecords from a test run
	 */
	def responseTimeStandardDeviation(data: Seq[ChartRequestRecord]): Long = {
		val avg = meanResponseTime(data)
		if (avg != NO_PLOT_MAGIC_VALUE) sqrt(data.map(result => pow(result.responseTime - avg, 2)).sum / data.length).toLong else NO_PLOT_MAGIC_VALUE
	}

	def minResponseTime(data: Seq[ChartRequestRecord]): Long = if (data.isEmpty) NO_PLOT_MAGIC_VALUE else data.minBy(_.responseTime).responseTime

	def maxResponseTime(data: Seq[ChartRequestRecord]): Long = if (data.isEmpty) NO_PLOT_MAGIC_VALUE else data.maxBy(_.responseTime).responseTime

	def computationByMillisecondAsList(data: SortedMap[Long, Seq[ChartRequestRecord]], requestStatus: RequestStatus, computation: Seq[ChartRequestRecord] => Long): List[(Long, Long)] =
		data
			.map { case (time, results) => time -> results.filter(_.requestStatus == requestStatus) }
			.map { case (time, results) => time -> computation(results) }
			.toList

	def responseTimeByMillisecondAsList(data: SortedMap[Long, Seq[ChartRequestRecord]], requestStatus: RequestStatus): List[(Long, Long)] = computationByMillisecondAsList(data, requestStatus, meanResponseTime)

	def latencyByMillisecondAsList(data: SortedMap[Long, Seq[ChartRequestRecord]], requestStatus: RequestStatus): List[(Long, Long)] = computationByMillisecondAsList(data, requestStatus, meanLatency)

	def numberOfRequestsPerSecond(data: SortedMap[Long, Seq[ChartRequestRecord]]): SortedMap[Long, Int] = data.map { case (time, results) => time -> results.length }

	def numberOfRequestsPerSecondAsList(data: SortedMap[Long, Seq[ChartRequestRecord]]): List[(Long, Int)] = numberOfRequestsPerSecond(data).toList

	def numberOfRequestsPerSecond(data: SortedMap[Long, Seq[ChartRequestRecord]], requestStatus: RequestStatus): List[(Long, Int)] =
		numberOfRequestsPerSecondAsList(data.map { case (time, results) => time -> results.filter(_.requestStatus == requestStatus) })

	def numberOfRequestInResponseTimeRange(data: Seq[ChartRequestRecord], lowerBound: Int, higherBound: Int): List[(String, Int)] = {

		val groupNames = List((1, "t < " + lowerBound + "ms"), (2, lowerBound + "ms < t < " + higherBound + "ms"), (3, higherBound + "ms < t"), (4, "failed"))
		val (firstGroup, mediumGroup, lastGroup, failedGroup) = (groupNames(0), groupNames(1), groupNames(2), groupNames(3))

		var grouped = data.groupBy {
			case result if (result.requestStatus == KO) => failedGroup
			case result if (result.responseTime < lowerBound) => firstGroup
			case result if (result.responseTime > higherBound) => lastGroup
			case _ => mediumGroup
		}

		// Add empty sections
		groupNames.map { name => grouped += (name -> grouped.getOrElse(name, Seq.empty)) }

		// Computes the number of requests per group
		// Then sorts the list by the order of the groupName
		// Then creates the list to be returned
		grouped
			.map { case (range, results) => (range, results.length) }
			.toList
			.sortBy { case ((rangeId, _), _) => rangeId }
			.map { case ((_, rangeName), count) => (rangeName, count) }
	}

	def respTimeAgainstNbOfReqPerSecond(requestsPerSecond: SortedMap[Long, Int], requestData: SortedMap[Long, Seq[ChartRequestRecord]], requestStatus: RequestStatus): List[(Int, Long)] = requestData
		.map {
			case (time, results) => results
				.filter(_.requestStatus == requestStatus)
				.map(requestsPerSecond.get(time).get -> _.responseTime)
		}.toList
		.flatten

	def numberOfActiveSessionsPerSecond(data: SortedMap[Long, Seq[ChartRequestRecord]]): List[(Long, Int)] = {

		@tailrec
		def countRec(data: List[(Long, Seq[ChartRequestRecord])], counts: List[(Long, Int)], currentCount: Int): List[(Long, Int)] = {
			data match {
				case Nil => counts
				case (time, results) :: otherData => {
					val starts = results.count(_.requestName == START_OF_SCENARIO)
					val ends = results.count(_.requestName == END_OF_SCENARIO)
					val newCount = currentCount + starts - ends
					countRec(otherData, (time, newCount) :: counts, newCount)
				}
			}
		}

		countRec(data.toList, Nil, 0).reverse
	}

	def responseTimeDistribution(records: Seq[ChartRequestRecord], minTime: Long, maxTime: Long, slotsNumber: Int, total: Int): Seq[(Long, Int)] = {

		val width = maxTime - minTime

		val step = max(width / slotsNumber, 1)
		val actualSlotNumber = if (step == 1) width.toInt else slotsNumber

		val percentiles = if (records.isEmpty)
			Map.empty[Long, Int]
		else
			records
				.groupBy(record => minTime + ((record.responseTime - minTime) / step) * step)
				.map { case (time, records) => time -> round(records.size * 100.0 / total).toInt }

		for (i <- 0 to actualSlotNumber) yield {
			val range = minTime + i * step
			(range -> percentiles.get(range).getOrElse(0))
		}
	}

	/**
	 * @param sortedRecords records, sorted by response time
	 * @param percent
	 * @return the percentile
	 */
	def responseTimePercentile(sortedRecords: Seq[ChartRequestRecord], percent: Double): Long = {
		val limitIndex = round(percent * sortedRecords.size + 0.5).toInt - 1
		if (sortedRecords.isEmpty) NO_PLOT_MAGIC_VALUE else sortedRecords(limitIndex).responseTime
	}

	def count(data: List[(Long, Int)]) = data.foldLeft(0)((sum, entry) => sum + entry._2)
	def count(data: SortedMap[Long, Seq[ChartRequestRecord]]) = data.foldLeft(0)((sum, entry) => sum + entry._2.length)
}