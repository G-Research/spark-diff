/*
 * Copyright 2020 G-Research
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.co.gresearch

import org.apache.spark.sql.functions._
import org.apache.spark.sql._

import scala.collection.generic.Sorted

package object spark {

  /**
   * Encloses the given strings with backticks if needed. Multiple strings will be enclosed individually and
   * concatenated with dots (`.`).
   *
   * This is useful when referencing column names that contain special characters like dots (`.`).
   *
   * Examples:
   * {{{
   *   col("a.column")                        // this references the field "column" of column "a"
   *   col("`a.column`")                      // this reference the column with the name "a.column"
   *   col(backticks("column"))               // produces "column"
   *   col(backticks("a.column"))             // produces "`a.column`"
   *   col(backticks("`a.column`"))           // produces "`a.column`"
   *   col(backticks("a.column", "a.field"))  // produces "`a.column`.`a.field`"
   * }}}
   *
   * @param string a string
   * @param strings more strings
   * @return
   */
  def backticks(string: String, strings: String*): String = (string +: strings)
    .map(s => if (s.contains(".") && !s.startsWith("`") && !s.endsWith("`")) s"`$s`" else s).mkString(".")

  /**
   * Implicit class to extend a Spark Dataset.
   *
   * @param df dataset or dataframe
   * @tparam D inner type of dataset
   */
  implicit class ExtendedDataFrame[D](df: Dataset[D]) {

    import df.sparkSession.implicits._

    /**
     * Compute the histogram of a column when aggregated by aggregate columns.
     * Thresholds are expected to be provided in ascending order.
     * The result dataframe contains the aggregate and histogram columns only.
     * For each threshold value in thresholds, there will be a column named s"≤$threshold".
     * There will also be a final column called s">${last_threshold}", that counts the remaining
     * values that exceed the last threshold.
     *
     * @param thresholds sequence of thresholds, must implement <= and > operators w.r.t. valueColumn
     * @param valueColumn histogram is computed for values of this column
     * @param aggregateColumns histogram is computed against these columns
     * @tparam T type of histogram thresholds
     * @return dataframe with aggregate and histogram columns
     */
    def histogram[T: Ordering](thresholds: Seq[T], valueColumn: Column, aggregateColumns: Column*): DataFrame = {
      if (thresholds.isEmpty)
        throw new IllegalArgumentException("Thresholds must not be empty")

      val bins = if (thresholds.length == 1) Seq.empty else thresholds.sliding(2).toSeq

      if (bins.exists(s => s.head == s.last))
        throw new IllegalArgumentException(s"Thresholds must not contain duplicates: ${thresholds.mkString(",")}")

      val ordering = implicitly[Ordering[T]]
      if (bins.exists(s => ordering.gt(s.head, s.last)))
        histogram(thresholds.sorted, valueColumn, aggregateColumns: _*)
      else
        df.toDF()
          .withColumn(s"≤${thresholds.head}", when(valueColumn <= thresholds.head, 1).otherwise(0))
          .call(
            bins.foldLeft(_) { case (df, bin) =>
              df.withColumn(s"≤${bin.last}", when(valueColumn > bin.head && $"value" <= bin.last, 1).otherwise(0))
            })
          .withColumn(s">${thresholds.last}", when(valueColumn > thresholds.last, 1).otherwise(0))
          .groupBy(aggregateColumns: _*)
          .agg(
            Some(thresholds.head).map(t => sum(backticks(s"≤$t")).as(s"≤$t")).get,
            thresholds.tail.map(t => sum(backticks(s"≤$t")).as(s"≤$t")) :+
              sum(backticks(s">${thresholds.last}")).as(s">${thresholds.last}") :_*
          )
    }

  }

}
