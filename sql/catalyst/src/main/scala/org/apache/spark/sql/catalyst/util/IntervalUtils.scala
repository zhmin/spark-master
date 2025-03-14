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

package org.apache.spark.sql.catalyst.util

import java.time.{Duration, Period}
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit

import scala.collection.mutable
import scala.util.control.NonFatal

import org.apache.spark.sql.catalyst.expressions.Literal
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser
import org.apache.spark.sql.catalyst.util.DateTimeConstants._
import org.apache.spark.sql.catalyst.util.IntervalStringStyles.{ANSI_STYLE, HIVE_STYLE, IntervalStyle}
import org.apache.spark.sql.errors.{QueryCompilationErrors, QueryExecutionErrors}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._
import org.apache.spark.sql.types.{DayTimeIntervalType => DT, Decimal, YearMonthIntervalType => YM}
import org.apache.spark.sql.types.DayTimeIntervalType.{DAY, HOUR, MINUTE, SECOND}
import org.apache.spark.sql.types.YearMonthIntervalType.{MONTH, YEAR}
import org.apache.spark.unsafe.types.{CalendarInterval, UTF8String}

// The style of textual representation of intervals
object IntervalStringStyles extends Enumeration {
  type IntervalStyle = Value
  val ANSI_STYLE, HIVE_STYLE = Value
}

object IntervalUtils extends SparkIntervalUtils {

  private val MAX_DAY = Long.MaxValue / MICROS_PER_DAY
  private val MAX_HOUR = Long.MaxValue / MICROS_PER_HOUR
  private val MAX_MINUTE = Long.MaxValue / MICROS_PER_MINUTE
  private val MAX_SECOND = Long.MaxValue / MICROS_PER_SECOND
  private val MIN_SECOND = Long.MinValue / MICROS_PER_SECOND

  def getYears(months: Int): Int = months / MONTHS_PER_YEAR

  def getYears(interval: CalendarInterval): Int = getYears(interval.months)

  def getMonths(months: Int): Byte = (months % MONTHS_PER_YEAR).toByte

  def getMonths(interval: CalendarInterval): Byte = getMonths(interval.months)

  def getDays(microseconds: Long): Int = (microseconds / MICROS_PER_DAY).toInt

  def getDays(interval: CalendarInterval): Int = {
    val daysInMicroseconds = getDays(interval.microseconds)
    Math.addExact(interval.days, daysInMicroseconds)
  }

  def getHours(microseconds: Long): Byte = {
    ((microseconds % MICROS_PER_DAY) / MICROS_PER_HOUR).toByte
  }

  def getHours(interval: CalendarInterval): Byte = getHours(interval.microseconds)

  def getMinutes(microseconds: Long): Byte = {
    ((microseconds % MICROS_PER_HOUR) / MICROS_PER_MINUTE).toByte
  }

  def getMinutes(interval: CalendarInterval): Byte = getMinutes(interval.microseconds)

  def getSeconds(microseconds: Long): Decimal = {
    Decimal(microseconds % MICROS_PER_MINUTE, 8, 6)
  }

  def getSeconds(interval: CalendarInterval): Decimal = getSeconds(interval.microseconds)

  private def toLongWithRange(
      fieldName: UTF8String,
      s: String,
      minValue: Long,
      maxValue: Long): Long = {
    val result = if (s == null) 0L else s.toLong
    require(minValue <= result && result <= maxValue,
      s"$fieldName $result outside range [$minValue, $maxValue]")

    result
  }

  private val yearMonthPatternString = "([+|-])?(\\d+)-(\\d+)"
  private val yearMonthRegex = (s"^$yearMonthPatternString$$").r
  private val yearMonthLiteralRegex =
    (s"(?i)^INTERVAL\\s+([+|-])?'$yearMonthPatternString'\\s+YEAR\\s+TO\\s+MONTH$$").r
  private val yearMonthIndividualPatternString = "([+|-])?(\\d+)"
  private val yearMonthIndividualRegex = (s"^$yearMonthIndividualPatternString$$").r
  private val yearMonthIndividualLiteralRegex =
    (s"(?i)^INTERVAL\\s+([+|-])?'$yearMonthIndividualPatternString'\\s+(YEAR|MONTH)$$").r

  private def finalSign(firstSign: String, secondSign: String = null): Int = {
    (firstSign, secondSign) match {
      case ("-", "-") => 1
      case ("-", _) => -1
      case (_, "-") => -1
      case (_, _) => 1
    }
  }

  private def throwIllegalIntervalFormatException(
      input: UTF8String,
      startFiled: Byte,
      endField: Byte,
      intervalStr: String,
      typeName: String,
      fallBackNotice: Option[String] = None) = {
    throw new IllegalArgumentException(
      s"Interval string does not match $intervalStr format of " +
        s"${supportedFormat((startFiled, endField)).map(format => s"`$format`").mkString(", ")} " +
        s"when cast to $typeName: ${input.toString}" +
        s"${fallBackNotice.map(s => s", $s").getOrElse("")}")
  }

  val supportedFormat = Map(
    (YM.YEAR, YM.MONTH) -> Seq("[+|-]y-m", "INTERVAL [+|-]'[+|-]y-m' YEAR TO MONTH"),
    (YM.YEAR, YM.YEAR) -> Seq("[+|-]y", "INTERVAL [+|-]'[+|-]y' YEAR"),
    (YM.MONTH, YM.MONTH) -> Seq("[+|-]m", "INTERVAL [+|-]'[+|-]m' MONTH"),
    (DT.DAY, DT.DAY) -> Seq("[+|-]d", "INTERVAL [+|-]'[+|-]d' DAY"),
    (DT.DAY, DT.HOUR) -> Seq("[+|-]d h", "INTERVAL [+|-]'[+|-]d h' DAY TO HOUR"),
    (DT.DAY, DT.MINUTE) -> Seq("[+|-]d h:m", "INTERVAL [+|-]'[+|-]d h:m' DAY TO MINUTE"),
    (DT.DAY, DT.SECOND) -> Seq("[+|-]d h:m:s.n", "INTERVAL [+|-]'[+|-]d h:m:s.n' DAY TO SECOND"),
    (DT.HOUR, DT.HOUR) -> Seq("[+|-]h", "INTERVAL [+|-]'[+|-]h' HOUR"),
    (DT.HOUR, DT.MINUTE) -> Seq("[+|-]h:m", "INTERVAL [+|-]'[+|-]h:m' HOUR TO MINUTE"),
    (DT.HOUR, DT.SECOND) -> Seq("[+|-]h:m:s.n", "INTERVAL [+|-]'[+|-]h:m:s.n' HOUR TO SECOND"),
    (DT.MINUTE, DT.MINUTE) -> Seq("[+|-]m", "INTERVAL [+|-]'[+|-]m' MINUTE"),
    (DT.MINUTE, DT.SECOND) -> Seq("[+|-]m:s.n", "INTERVAL [+|-]'[+|-]m:s.n' MINUTE TO SECOND"),
    (DT.SECOND, DT.SECOND) -> Seq("[+|-]s.n", "INTERVAL [+|-]'[+|-]s.n' SECOND")
  )

  def castStringToYMInterval(
      input: UTF8String,
      startField: Byte,
      endField: Byte): Int = {

    def checkTargetType(targetStartField: Byte, targetEndField: Byte): Boolean =
      startField == targetStartField && endField == targetEndField

    input.trimAll().toString match {
      case yearMonthRegex(sign, year, month) if checkTargetType(YM.YEAR, YM.MONTH) =>
        toYMInterval(year, month, finalSign(sign))
      case yearMonthLiteralRegex(firstSign, secondSign, year, month)
        if checkTargetType(YM.YEAR, YM.MONTH) =>
        toYMInterval(year, month, finalSign(firstSign, secondSign))
      case yearMonthIndividualRegex(firstSign, value) =>
        safeToInterval("year-month") {
          val sign = finalSign(firstSign)
          if (endField == YM.YEAR) {
            sign * Math.toIntExact(value.toLong * MONTHS_PER_YEAR)
          } else if (startField == YM.MONTH) {
            Math.toIntExact(sign * value.toLong)
          } else {
            throwIllegalIntervalFormatException(
              input, startField, endField, "year-month", YM(startField, endField).typeName)
          }
        }
      case yearMonthIndividualLiteralRegex(firstSign, secondSign, value, unit) =>
        safeToInterval("year-month") {
          val sign = finalSign(firstSign, secondSign)
          unit.toUpperCase(Locale.ROOT) match {
            case "YEAR" if checkTargetType(YM.YEAR, YM.YEAR) =>
              sign * Math.toIntExact(value.toLong * MONTHS_PER_YEAR)
            case "MONTH" if checkTargetType(YM.MONTH, YM.MONTH) =>
              Math.toIntExact(sign * value.toLong)
            case _ => throwIllegalIntervalFormatException(input, startField, endField,
              "year-month", YM(startField, endField).typeName)
          }
        }
      case _ => throwIllegalIntervalFormatException(input, startField, endField,
        "year-month", YM(startField, endField).typeName)
    }
  }

  /**
   * Parse year-month interval in form: [+|-]YYYY-MM
   *
   * adapted from HiveIntervalYearMonth.valueOf
   */
  def fromYearMonthString(input: String): CalendarInterval = {
    fromYearMonthString(input, YM.YEAR, YM.MONTH)
  }

  /**
   * Parse year-month interval in form: [+|-]YYYY-MM
   *
   * adapted from HiveIntervalYearMonth.valueOf
   * Below interval conversion patterns are supported:
   * - YEAR TO (YEAR|MONTH)
   */
  def fromYearMonthString(input: String, startField: Byte, endField: Byte): CalendarInterval = {
    require(input != null, "Interval year-month string must be not null")
    val months = castStringToYMInterval(UTF8String.fromString(input), startField, endField)
    new CalendarInterval(months, 0, 0)
  }

  private def safeToInterval[T](interval: String)(f: => T): T = {
    try {
      f
    } catch {
      case NonFatal(e) =>
        throw new IllegalArgumentException(
          s"Error parsing interval $interval string: ${e.getMessage}", e)
    }
  }

  private def toYMInterval(year: String, month: String, sign: Int): Int = {
    safeToInterval("year-month") {
      val years = toLongWithRange(yearStr, year, 0, Integer.MAX_VALUE / MONTHS_PER_YEAR)
      val totalMonths =
        sign * (years * MONTHS_PER_YEAR + toLongWithRange(monthStr, month, 0, 11))
      Math.toIntExact(totalMonths)
    }
  }

  private val normalPattern = "(\\d{1,2})"
  private val dayBoundPattern = "(\\d{1,9})"
  private val hourBoundPattern = "(\\d{1,10})"
  private val minuteBoundPattern = "(\\d{1,12})"
  private val secondBoundPattern = "(\\d{1,13})"
  private val microPattern = "(\\.\\d{1,9})"

  private val dayHourPatternString = s"([+|-])?$dayBoundPattern $normalPattern"
  private val dayHourRegex = (s"^$dayHourPatternString$$").r
  private val dayHourLiteralRegex =
    (s"(?i)^INTERVAL\\s+([+|-])?\\'$dayHourPatternString\\'\\s+DAY\\s+TO\\s+HOUR$$").r

  private val dayMinutePatternString = s"([+|-])?$dayBoundPattern $normalPattern:$normalPattern"
  private val dayMinuteRegex = (s"^$dayMinutePatternString$$").r
  private val dayMinuteLiteralRegex =
    (s"(?i)^INTERVAL\\s+([+|-])?\\'$dayMinutePatternString\\'\\s+DAY\\s+TO\\s+MINUTE$$").r

  private val daySecondPatternString =
    s"([+|-])?$dayBoundPattern $normalPattern:$normalPattern:$normalPattern$microPattern?"
  private val daySecondRegex = (s"^$daySecondPatternString$$").r
  private val daySecondLiteralRegex =
    (s"(?i)^INTERVAL\\s+([+|-])?\\'$daySecondPatternString\\'\\s+DAY\\s+TO\\s+SECOND$$").r

  private val hourMinutePatternString = s"([+|-])?$hourBoundPattern:$normalPattern"
  private val hourMinuteRegex = (s"^$hourMinutePatternString$$").r
  private val hourMinuteLiteralRegex =
    (s"(?i)^INTERVAL\\s+([+|-])?\\'$hourMinutePatternString\\'\\s+HOUR\\s+TO\\s+MINUTE$$").r

  private val hourSecondPatternString =
    s"([+|-])?$hourBoundPattern:$normalPattern:$normalPattern$microPattern?"
  private val hourSecondRegex = (s"^$hourSecondPatternString$$").r
  private val hourSecondLiteralRegex =
    (s"(?i)^INTERVAL\\s+([+|-])?\\'$hourSecondPatternString\\'\\s+HOUR\\s+TO\\s+SECOND$$").r

  private val minuteSecondPatternString =
    s"([+|-])?$minuteBoundPattern:$normalPattern$microPattern?"
  private val minuteSecondRegex = (s"^$minuteSecondPatternString$$").r
  private val minuteSecondLiteralRegex =
    (s"(?i)^INTERVAL\\s+([+|-])?\\'$minuteSecondPatternString\\'\\s+MINUTE\\s+TO\\s+SECOND$$").r

  private val dayTimeIndividualPatternString = s"([+|-])?$secondBoundPattern(\\.\\d{1,9})?"
  private val dayTimeIndividualRegex = (s"^$dayTimeIndividualPatternString$$").r
  private val dayTimeIndividualLiteralRegex =
    (s"(?i)^INTERVAL\\s+([+|-])?'$dayTimeIndividualPatternString'\\s+(DAY|HOUR|MINUTE|SECOND)$$").r

  def castStringToDTInterval(
      input: UTF8String,
      startField: Byte,
      endField: Byte): Long = {

    def secondAndMicro(second: String, micro: String): String = {
      if (micro != null) {
        s"$second$micro"
      } else {
        second
      }
    }

    def checkTargetType(targetStartField: Byte, targetEndField: Byte): Boolean =
      startField == targetStartField && endField == targetEndField

    input.trimAll().toString match {
      case dayHourRegex(sign, day, hour) if checkTargetType(DT.DAY, DT.HOUR) =>
        toDTInterval(day, hour, "0", "0", finalSign(sign))
      case dayHourLiteralRegex(firstSign, secondSign, day, hour)
        if checkTargetType(DT.DAY, DT.HOUR) =>
        toDTInterval(day, hour, "0", "0", finalSign(firstSign, secondSign))
      case dayMinuteRegex(sign, day, hour, minute) if checkTargetType(DT.DAY, DT.MINUTE) =>
        toDTInterval(day, hour, minute, "0", finalSign(sign))
      case dayMinuteLiteralRegex(firstSign, secondSign, day, hour, minute)
        if checkTargetType(DT.DAY, DT.MINUTE) =>
        toDTInterval(day, hour, minute, "0", finalSign(firstSign, secondSign))
      case daySecondRegex(sign, day, hour, minute, second, micro)
        if checkTargetType(DT.DAY, DT.SECOND) =>
        toDTInterval(day, hour, minute, secondAndMicro(second, micro), finalSign(sign))
      case daySecondLiteralRegex(firstSign, secondSign, day, hour, minute, second, micro)
        if checkTargetType(DT.DAY, DT.SECOND) =>
        toDTInterval(day, hour, minute, secondAndMicro(second, micro),
          finalSign(firstSign, secondSign))

      case hourMinuteRegex(sign, hour, minute) if checkTargetType(DT.HOUR, DT.MINUTE) =>
        toDTInterval(hour, minute, "0", finalSign(sign))
      case hourMinuteLiteralRegex(firstSign, secondSign, hour, minute)
        if checkTargetType(DT.HOUR, DT.MINUTE) =>
        toDTInterval(hour, minute, "0", finalSign(firstSign, secondSign))
      case hourSecondRegex(sign, hour, minute, second, micro)
        if checkTargetType(DT.HOUR, DT.SECOND) =>
        toDTInterval(hour, minute, secondAndMicro(second, micro), finalSign(sign))
      case hourSecondLiteralRegex(firstSign, secondSign, hour, minute, second, micro)
        if checkTargetType(DT.HOUR, DT.SECOND) =>
        toDTInterval(hour, minute, secondAndMicro(second, micro), finalSign(firstSign, secondSign))

      case minuteSecondRegex(sign, minute, second, micro)
        if checkTargetType(DT.MINUTE, DT.SECOND) =>
        toDTInterval(minute, secondAndMicro(second, micro), finalSign(sign))
      case minuteSecondLiteralRegex(firstSign, secondSign, minute, second, micro)
        if checkTargetType(DT.MINUTE, DT.SECOND) =>
        toDTInterval(minute, secondAndMicro(second, micro), finalSign(firstSign, secondSign))

      case dayTimeIndividualRegex(firstSign, value, suffix) =>
        safeToInterval("day-time") {
          val sign = finalSign(firstSign)
          (startField, endField) match {
            case (DT.DAY, DT.DAY) if suffix == null && value.length <= 9 =>
              sign * value.toLong * MICROS_PER_DAY
            case (DT.HOUR, DT.HOUR) if suffix == null && value.length <= 10 =>
              sign * value.toLong * MICROS_PER_HOUR
            case (DT.MINUTE, DT.MINUTE) if suffix == null && value.length <= 12 =>
              sign * value.toLong * MICROS_PER_MINUTE
            case (DT.SECOND, DT.SECOND) if value.length <= 13 =>
              sign match {
                case 1 => parseSecondNano(secondAndMicro(value, suffix))
                case -1 => parseSecondNano(s"-${secondAndMicro(value, suffix)}")
              }
            case (_, _) => throwIllegalIntervalFormatException(input, startField, endField,
              "day-time", DT(startField, endField).typeName, Some(fallbackNotice))
          }
        }
      case dayTimeIndividualLiteralRegex(firstSign, secondSign, value, suffix, unit) =>
        safeToInterval("day-time") {
          val sign = finalSign(firstSign, secondSign)
          unit.toUpperCase(Locale.ROOT) match {
            case "DAY" if suffix == null && value.length <= 9 && checkTargetType(DT.DAY, DT.DAY) =>
              sign * value.toLong * MICROS_PER_DAY
            case "HOUR" if suffix == null && value.length <= 10
              && checkTargetType(DT.HOUR, DT.HOUR) =>
              sign * value.toLong * MICROS_PER_HOUR
            case "MINUTE" if suffix == null && value.length <= 12
              && checkTargetType(DT.MINUTE, DT.MINUTE) =>
              sign * value.toLong * MICROS_PER_MINUTE
            case "SECOND" if value.length <= 13 && checkTargetType(DT.SECOND, DT.SECOND) =>
              sign match {
                case 1 => parseSecondNano(secondAndMicro(value, suffix))
                case -1 => parseSecondNano(s"-${secondAndMicro(value, suffix)}")
              }
            case _ => throwIllegalIntervalFormatException(input, startField, endField,
              "day-time", DT(startField, endField).typeName, Some(fallbackNotice))
          }
        }
      case _ => throwIllegalIntervalFormatException(input, startField, endField,
        "day-time", DT(startField, endField).typeName, Some(fallbackNotice))
    }
  }

  def toDTInterval(day: String, hour: String, minute: String, second: String, sign: Int): Long = {
    var micros = 0L
    val days = toLongWithRange(dayStr, day, 0, MAX_DAY).toInt
    micros = Math.addExact(micros, sign * days * MICROS_PER_DAY)
    val hours = toLongWithRange(hourStr, hour, 0, 23)
    micros = Math.addExact(micros, sign * hours * MICROS_PER_HOUR)
    val minutes = toLongWithRange(minuteStr, minute, 0, 59)
    micros = Math.addExact(micros, sign * minutes * MICROS_PER_MINUTE)
    micros = Math.addExact(micros, sign * parseSecondNano(second, 0, 59))
    micros
  }

  def toDTInterval(hour: String, minute: String, second: String, sign: Int): Long = {
    var micros = 0L
    val hours = toLongWithRange(hourStr, hour, 0, MAX_HOUR)
    micros = Math.addExact(micros, sign * hours * MICROS_PER_HOUR)
    val minutes = toLongWithRange(minuteStr, minute, 0, 59)
    micros = Math.addExact(micros, sign * minutes * MICROS_PER_MINUTE)
    micros = Math.addExact(micros, sign * parseSecondNano(second, 0, 59))
    micros
  }

  def toDTInterval(minute: String, second: String, sign: Int): Long = {
    var micros = 0L
    val minutes = toLongWithRange(minuteStr, minute, 0, MAX_MINUTE)
    micros = Math.addExact(micros, sign * minutes * MICROS_PER_MINUTE)
    micros = Math.addExact(micros, sign * parseSecondNano(second, 0, 59))
    micros
  }

  def castDayTimeStringToInterval(
      input: String,
      startField: Byte,
      endField: Byte): CalendarInterval = {
    val micros = castStringToDTInterval(UTF8String.fromString(input), startField, endField)
    new CalendarInterval(0, (micros / MICROS_PER_DAY).toInt, micros % MICROS_PER_DAY)
  }

  /**
   * Parse day-time interval in form: [-]d HH:mm:ss.nnnnnnnnn and [-]HH:mm:ss.nnnnnnnnn
   *
   * adapted from HiveIntervalDayTime.valueOf
   */
  def fromDayTimeString(s: String): CalendarInterval = {
    fromDayTimeString(s, DT.DAY, DT.SECOND)
  }

  /**
   * Parse day-time interval in form: [-]d HH:mm:ss.nnnnnnnnn and [-]HH:mm:ss.nnnnnnnnn
   *
   * adapted from HiveIntervalDayTime.valueOf.
   * Below interval conversion patterns are supported:
   * - DAY TO (DAY|HOUR|MINUTE|SECOND)
   * - HOUR TO (HOUR|MINUTE|SECOND)
   * - MINUTE TO (MINUTE|SECOND)
   */
  def fromDayTimeString(input: String, from: Byte, to: Byte): CalendarInterval = {
    require(input != null, "Interval day-time string must be not null")
    if (SQLConf.get.getConf(SQLConf.LEGACY_FROM_DAYTIME_STRING)) {
      parseDayTimeLegacy(input, from, to)
    } else {
      castDayTimeStringToInterval(input, from, to)
    }
  }

  /**
   * Parse all kinds of interval literals including unit-to-unit form and unit list form
   */
  def fromIntervalString(input: String): CalendarInterval = try {
    if (input.toLowerCase(Locale.ROOT).trim.startsWith("interval")) {
      CatalystSqlParser.parseExpression(input) match {
        case Literal(months: Int, _: YearMonthIntervalType) => new CalendarInterval(months, 0, 0)
        case Literal(micros: Long, _: DayTimeIntervalType) => new CalendarInterval(0, 0, micros)
        case Literal(cal: CalendarInterval, CalendarIntervalType) => cal
      }
    } else {
      stringToInterval(UTF8String.fromString(input))
    }
  } catch {
    case NonFatal(e) =>
      throw QueryCompilationErrors.cannotParseIntervalError(input, e)
  }

  private val dayTimePatternLegacy =
    "^([+|-])?((\\d+) )?((\\d+):)?(\\d+):(\\d+)(\\.(\\d+))?$".r

  private val fallbackNotice = s"set ${SQLConf.LEGACY_FROM_DAYTIME_STRING.key} to true " +
    "to restore the behavior before Spark 3.0."

  /**
   * Legacy method of parsing a string in a day-time format. It ignores the `from` bound,
   * and takes into account only the `to` bound by truncating the result. For example,
   * if the input string is "2 12:30:15", `from` is "hour" and `to` is "second", the result
   * is "2 days 12 hours 30 minutes".
   *
   * @param input The day-time string
   * @param from The interval units from which the input strings begins
   * @param to The interval units at which the input string ends
   * @return an instance of `CalendarInterval` if parsing completes successfully otherwise
   *         the exception `IllegalArgumentException` is raised.
   */
  private def parseDayTimeLegacy(
      input: String,
      from: Byte,
      to: Byte): CalendarInterval = {
    assert(input.length == input.trim.length)
    val m = dayTimePatternLegacy.pattern.matcher(input)
    require(m.matches, s"Interval string must match day-time format of 'd h:m:s.n': $input, " +
      s"$fallbackNotice")

    try {
      val sign = if (m.group(1) != null && m.group(1) == "-") -1 else 1
      val days = if (m.group(2) == null) {
        0
      } else {
        toLongWithRange(dayStr, m.group(3), 0, Integer.MAX_VALUE).toInt
      }
      var hours: Long = 0L
      var minutes: Long = 0L
      var seconds: Long = 0L
      if (m.group(5) != null || from == DT.MINUTE) { // 'HH:mm:ss' or 'mm:ss minute'
        hours = toLongWithRange(hourStr, m.group(5), 0, 23)
        minutes = toLongWithRange(minuteStr, m.group(6), 0, 59)
        seconds = toLongWithRange(secondStr, m.group(7), 0, 59)
      } else if (m.group(8) != null) { // 'mm:ss.nn'
        minutes = toLongWithRange(minuteStr, m.group(6), 0, 59)
        seconds = toLongWithRange(secondStr, m.group(7), 0, 59)
      } else { // 'HH:mm'
        hours = toLongWithRange(hourStr, m.group(6), 0, 23)
        minutes = toLongWithRange(secondStr, m.group(7), 0, 59)
      }
      // Hive allow nanosecond precision interval
      var secondsFraction = parseNanos(m.group(9), seconds < 0)
      to match {
        case DT.HOUR =>
          minutes = 0
          seconds = 0
          secondsFraction = 0
        case DT.MINUTE =>
          seconds = 0
          secondsFraction = 0
        case DT.SECOND =>
          // No-op
        case _ => throw new IllegalArgumentException(s"Cannot support (" +
          s"interval '$input' ${DT.fieldToString(from)} to ${DT.fieldToString(to)}) expression")
      }
      var micros = secondsFraction
      micros = Math.addExact(micros, Math.multiplyExact(hours, MICROS_PER_HOUR))
      micros = Math.addExact(micros, Math.multiplyExact(minutes, MICROS_PER_MINUTE))
      micros = Math.addExact(micros, Math.multiplyExact(seconds, MICROS_PER_SECOND))
      new CalendarInterval(0, sign * days, sign * micros)
    } catch {
      case e: Exception =>
        throw new IllegalArgumentException(
          s"Error parsing interval day-time string: ${e.getMessage}", e)
    }
  }

  // Parses a string with nanoseconds, truncates the result and returns microseconds
  private def parseNanos(nanos: String, isNegative: Boolean): Long = {
    if (nanos != null) {
      val maxNanosLen = 9
      val alignedStr = if (nanos.length < maxNanosLen) {
        (nanos + "000000000").substring(0, maxNanosLen)
      } else nanos
      val nanoSecond = toLongWithRange(nanosStr, alignedStr, 0L, 999999999L)
      val microSecond = nanoSecond / NANOS_PER_MICROS
      if (isNegative) -microSecond else microSecond
    } else {
      0L
    }
  }

  /**
   * Parse second_nano string in ss.nnnnnnnnn format to microseconds
   */
  private def parseSecondNano(
      secondNano: String,
      minSecond: Long = MIN_SECOND,
      maxSecond: Long = MAX_SECOND): Long = {
    def parseSeconds(secondsStr: String): Long = {
      toLongWithRange(secondStr, secondsStr, minSecond, maxSecond) * MICROS_PER_SECOND
    }

    secondNano.split("\\.") match {
      case Array(secondsStr) => parseSeconds(secondsStr)
      case Array("", nanosStr) => parseNanos(nanosStr, false)
      case Array(secondsStr, nanosStr) =>
        val seconds = parseSeconds(secondsStr)
        Math.addExact(seconds, parseNanos(nanosStr, seconds < 0))
      case _ =>
        throw new IllegalArgumentException(
          "Interval string does not match second-nano format of ss.nnnnnnnnn")
    }
  }

  /**
   * Gets interval duration
   *
   * @param interval The interval to get duration
   * @param targetUnit Time units of the result
   * @param daysPerMonth The number of days per one month. The default value is 31 days
   *                     per month. This value was taken as the default because it is used
   *                     in Structured Streaming for watermark calculations. Having 31 days
   *                     per month, we can guarantee that events are not dropped before
   *                     the end of any month (February with 29 days or January with 31 days).
   * @return Duration in the specified time units
   */
  def getDuration(
      interval: CalendarInterval,
      targetUnit: TimeUnit,
      daysPerMonth: Int = 31): Long = {
    val monthsDuration = Math.multiplyExact(
      daysPerMonth * MICROS_PER_DAY,
      interval.months)
    val daysDuration = Math.multiplyExact(
      MICROS_PER_DAY,
      interval.days)
    val result = Math.addExact(interval.microseconds, Math.addExact(daysDuration, monthsDuration))
    targetUnit.convert(result, TimeUnit.MICROSECONDS)
  }

  /**
   * Checks the interval is negative
   *
   * @param interval The checked interval
   * @param daysPerMonth The number of days per one month. The default value is 31 days
   *                     per month. This value was taken as the default because it is used
   *                     in Structured Streaming for watermark calculations. Having 31 days
   *                     per month, we can guarantee that events are not dropped before
   *                     the end of any month (February with 29 days or January with 31 days).
   * @return true if duration of the given interval is less than 0 otherwise false
   */
  def isNegative(interval: CalendarInterval, daysPerMonth: Int = 31): Boolean = {
    getDuration(interval, TimeUnit.MICROSECONDS, daysPerMonth) < 0
  }

  /**
   * Makes an interval from months, days and micros with the fractional part.
   * The overflow style here follows the way of ansi sql standard and the natural rules for
   * intervals as defined in the Gregorian calendar. Thus, the days fraction will be added
   * to microseconds but the months fraction will not be added to days, and it will throw
   * exception if any part overflows.
   */
  private def fromDoubles(
      monthsWithFraction: Double,
      daysWithFraction: Double,
      microsWithFraction: Double): CalendarInterval = {
    val truncatedMonths = MathUtils.toIntExact(monthsWithFraction.toLong)
    val truncatedDays = MathUtils.toIntExact(daysWithFraction.toLong)
    val micros = microsWithFraction + MICROS_PER_DAY * (daysWithFraction - truncatedDays)
    new CalendarInterval(truncatedMonths, truncatedDays, micros.round)
  }

  /**
   * Makes an interval from months, days and micros with the fractional part.
   * The overflow style here follows the way of casting [[java.lang.Double]] to integrals and the
   * natural rules for intervals as defined in the Gregorian calendar. Thus, the days fraction
   * will be added to microseconds but the months fraction will not be added to days, and there may
   * be rounding or truncation in months(or day and microseconds) part.
   */
  private def safeFromDoubles(
      monthsWithFraction: Double,
      daysWithFraction: Double,
      microsWithFraction: Double): CalendarInterval = {
    val truncatedDays = daysWithFraction.toInt
    val micros = microsWithFraction + MICROS_PER_DAY * (daysWithFraction - truncatedDays)
    new CalendarInterval(monthsWithFraction.toInt, truncatedDays, micros.round)
  }

  /**
   * Unary minus, return the negated the calendar interval value.
   *
   * @throws ArithmeticException if the result overflows any field value
   */
  def negateExact(interval: CalendarInterval): CalendarInterval = {
    val months = MathUtils.negateExact(interval.months)
    val days = MathUtils.negateExact(interval.days)
    val microseconds = MathUtils.negateExact(interval.microseconds)
    new CalendarInterval(months, days, microseconds)
  }

  /**
   * Unary minus, return the negated the calendar interval value.
   */
  def negate(interval: CalendarInterval): CalendarInterval = {
    new CalendarInterval(-interval.months, -interval.days, -interval.microseconds)
  }

  /**
   * Return a new calendar interval instance of the sum of two intervals.
   *
   * @throws ArithmeticException if the result overflows any field value
   */
  def addExact(left: CalendarInterval, right: CalendarInterval): CalendarInterval = {
    val months = MathUtils.addExact(left.months, right.months)
    val days = MathUtils.addExact(left.days, right.days)
    val microseconds = MathUtils.addExact(left.microseconds, right.microseconds)
    new CalendarInterval(months, days, microseconds)
  }

  /**
   * Return a new calendar interval instance of the sum of two intervals.
   */
  def add(left: CalendarInterval, right: CalendarInterval): CalendarInterval = {
    val months = left.months + right.months
    val days = left.days + right.days
    val microseconds = left.microseconds + right.microseconds
    new CalendarInterval(months, days, microseconds)
  }

  /**
   * Return a new calendar interval instance of the left interval minus the right one.
   *
   * @throws ArithmeticException if the result overflows any field value
   */
  def subtractExact(left: CalendarInterval, right: CalendarInterval): CalendarInterval = {
    val months = MathUtils.subtractExact(left.months, right.months)
    val days = MathUtils.subtractExact(left.days, right.days)
    val microseconds = MathUtils.subtractExact(left.microseconds, right.microseconds)
    new CalendarInterval(months, days, microseconds)
  }

  /**
   * Return a new calendar interval instance of the left interval minus the right one.
   */
  def subtract(left: CalendarInterval, right: CalendarInterval): CalendarInterval = {
    val months = left.months - right.months
    val days = left.days - right.days
    val microseconds = left.microseconds - right.microseconds
    new CalendarInterval(months, days, microseconds)
  }

  /**
   * Return a new calendar interval instance of the left interval times a multiplier.
   */
  def multiply(interval: CalendarInterval, num: Double): CalendarInterval = {
    safeFromDoubles(num * interval.months, num * interval.days, num * interval.microseconds)
  }

  /**
   * Return a new calendar interval instance of the left interval times a multiplier.
   *
   * @throws ArithmeticException if the result overflows any field value
   */
  def multiplyExact(interval: CalendarInterval, num: Double): CalendarInterval = {
    fromDoubles(num * interval.months, num * interval.days, num * interval.microseconds)
  }

  /**
   * Return a new calendar interval instance of the left interval divides by a dividend.
   */
  def divide(interval: CalendarInterval, num: Double): CalendarInterval = {
    if (num == 0) return null
    safeFromDoubles(interval.months / num, interval.days / num, interval.microseconds / num)
  }

  /**
   * Return a new calendar interval instance of the left interval divides by a dividend.
   *
   * @throws ArithmeticException if the result overflows any field value or divided by zero
   */
  def divideExact(interval: CalendarInterval, num: Double): CalendarInterval = {
    if (num == 0) throw QueryExecutionErrors.intervalDividedByZeroError(null)
    fromDoubles(interval.months / num, interval.days / num, interval.microseconds / num)
  }

  /**
   * A safe version of `stringToInterval`. It returns null for invalid input string.
   */
  def safeStringToInterval(input: UTF8String): CalendarInterval = {
    try {
      stringToInterval(input)
    } catch {
      case _: IllegalArgumentException => null
    }
  }

  def makeInterval(
      years: Int,
      months: Int,
      weeks: Int,
      days: Int,
      hours: Int,
      mins: Int,
      secs: Decimal): CalendarInterval = {
    val totalMonths = Math.addExact(months, Math.multiplyExact(years, MONTHS_PER_YEAR))
    val totalDays = Math.addExact(days, Math.multiplyExact(weeks, DAYS_PER_WEEK))
    assert(secs.scale == 6, "Seconds fractional must have 6 digits for microseconds")
    var micros = secs.toUnscaledLong
    micros = Math.addExact(micros, Math.multiplyExact(hours, MICROS_PER_HOUR))
    micros = Math.addExact(micros, Math.multiplyExact(mins, MICROS_PER_MINUTE))

    new CalendarInterval(totalMonths, totalDays, micros)
  }

  def makeDayTimeInterval(
      days: Int,
      hours: Int,
      mins: Int,
      secs: Decimal): Long = {
    assert(secs.scale == 6, "Seconds fractional must have 6 digits for microseconds")
    var micros = secs.toUnscaledLong
    micros = Math.addExact(micros, Math.multiplyExact(days, MICROS_PER_DAY))
    micros = Math.addExact(micros, Math.multiplyExact(hours, MICROS_PER_HOUR))
    micros = Math.addExact(micros, Math.multiplyExact(mins, MICROS_PER_MINUTE))
    micros
  }

  // The amount of seconds that can cause overflow in the conversion to microseconds
  private final val minDurationSeconds = Math.floorDiv(Long.MinValue, MICROS_PER_SECOND)

  /**
   * Converts this duration to the total length in microseconds.
   * <p>
   * If this duration is too large to fit in a [[Long]] microseconds, then an
   * exception is thrown.
   * <p>
   * If this duration has greater than microsecond precision, then the conversion
   * will drop any excess precision information as though the amount in nanoseconds
   * was subject to integer division by one thousand.
   *
   * @return The total length of the duration in microseconds
   * @throws ArithmeticException If numeric overflow occurs
   */
  def durationToMicros(duration: Duration): Long = {
    durationToMicros(duration, DT.SECOND)
  }

  def durationToMicros(duration: Duration, endField: Byte): Long = {
    val seconds = duration.getSeconds
    val micros = if (seconds == minDurationSeconds) {
      val microsInSeconds = (minDurationSeconds + 1) * MICROS_PER_SECOND
      val nanoAdjustment = duration.getNano
      assert(0 <= nanoAdjustment && nanoAdjustment < NANOS_PER_SECOND,
        "Duration.getNano() must return the adjustment to the seconds field " +
          "in the range from 0 to 999999999 nanoseconds, inclusive.")
      Math.addExact(microsInSeconds, (nanoAdjustment - NANOS_PER_SECOND) / NANOS_PER_MICROS)
    } else {
      val microsInSeconds = Math.multiplyExact(seconds, MICROS_PER_SECOND)
      Math.addExact(microsInSeconds, duration.getNano / NANOS_PER_MICROS)
    }

    endField match {
      case DT.DAY => micros - micros % MICROS_PER_DAY
      case DT.HOUR => micros - micros % MICROS_PER_HOUR
      case DT.MINUTE => micros - micros % MICROS_PER_MINUTE
      case DT.SECOND => micros
    }
  }

  /**
   * Obtains a [[Duration]] representing a number of microseconds.
   *
   * @param micros The number of microseconds, positive or negative
   * @return A [[Duration]], not null
   */
  def microsToDuration(micros: Long): Duration = Duration.of(micros, ChronoUnit.MICROS)

  /**
   * Gets the total number of months in this period.
   * <p>
   * This returns the total number of months in the period by multiplying the
   * number of years by 12 and adding the number of months.
   * <p>
   *
   * @return The total number of months in the period, may be negative
   * @throws ArithmeticException If numeric overflow occurs
   */
  def periodToMonths(period: Period): Int = {
    periodToMonths(period, YM.MONTH)
  }

  def periodToMonths(period: Period, endField: Byte): Int = {
    val monthsInYears = Math.multiplyExact(period.getYears, MONTHS_PER_YEAR)
    val months = Math.addExact(monthsInYears, period.getMonths)
    if (endField == YM.YEAR) {
      months - months % MONTHS_PER_YEAR
    } else {
      months
    }
  }

  /**
   * Obtains a [[Period]] representing a number of months. The days unit will be zero, and the years
   * and months units will be normalized.
   *
   * <p>
   * The months unit is adjusted to have an absolute value < 12, with the years unit being adjusted
   * to compensate. For example, the method returns "2 years and 3 months" for the 27 input months.
   * <p>
   * The sign of the years and months units will be the same after normalization.
   * For example, -13 months will be converted to "-1 year and -1 month".
   *
   * @param months The number of months, positive or negative
   * @return The period of months, not null
   */
  def monthsToPeriod(months: Int): Period = Period.ofMonths(months).normalized()

  /**
   * Converts an year-month interval as a number of months to its textual representation
   * which conforms to the ANSI SQL standard.
   *
   * @param months The number of months, positive or negative
   * @param style The style of textual representation of the interval
   * @param startField The start field (YEAR or MONTH) which the interval comprises of.
   * @param endField The end field (YEAR or MONTH) which the interval comprises of.
   * @return Year-month interval string
   */
  def toYearMonthIntervalString(
      months: Int,
      style: IntervalStyle,
      startField: Byte,
      endField: Byte): String = {
    var sign = ""
    var absMonths: Long = months
    if (months < 0) {
      sign = "-"
      absMonths = -absMonths
    }
    val year = s"$sign${absMonths / MONTHS_PER_YEAR}"
    val yearAndMonth = s"$year-${absMonths % MONTHS_PER_YEAR}"
    style match {
      case ANSI_STYLE =>
        val formatBuilder = new StringBuilder("INTERVAL '")
        if (startField == endField) {
          startField match {
            case YM.YEAR => formatBuilder.append(s"$year' YEAR")
            case YM.MONTH => formatBuilder.append(s"$months' MONTH")
          }
        } else {
          formatBuilder.append(s"$yearAndMonth' YEAR TO MONTH")
        }
        formatBuilder.toString
      case HIVE_STYLE => s"$yearAndMonth"
    }
  }

  /**
   * Converts a day-time interval as a number of microseconds to its textual representation
   * which conforms to the ANSI SQL standard.
   *
   * @param micros The number of microseconds, positive or negative
   * @param style The style of textual representation of the interval
   * @param startField The start field (DAY, HOUR, MINUTE, SECOND) which the interval comprises of.
   * @param endField The end field (DAY, HOUR, MINUTE, SECOND) which the interval comprises of.
   * @return Day-time interval string
   */
  def toDayTimeIntervalString(
      micros: Long,
      style: IntervalStyle,
      startField: Byte,
      endField: Byte): String = {
    var sign = ""
    var rest = micros
    // scalastyle:off caselocale
    val from = DT.fieldToString(startField).toUpperCase
    val to = DT.fieldToString(endField).toUpperCase
    // scalastyle:on caselocale
    val prefix = "INTERVAL '"
    val postfix = s"' ${if (startField == endField) from else s"$from TO $to"}"

    if (micros < 0) {
      if (micros == Long.MinValue) {
        // Especial handling of minimum `Long` value because negate op overflows `Long`.
        // seconds = 106751991 * (24 * 60 * 60) + 4 * 60 * 60 + 54 = 9223372036854
        // microseconds = -9223372036854000000L-775808 == Long.MinValue
        val baseStr = "-106751991 04:00:54.775808000"
        val minIntervalString = style match {
          case ANSI_STYLE =>
            val firstStr = startField match {
              case DT.DAY => s"-$MAX_DAY"
              case DT.HOUR => s"-$MAX_HOUR"
              case DT.MINUTE => s"-$MAX_MINUTE"
              case DT.SECOND => s"-$MAX_SECOND.775808"
            }
            val followingStr = if (startField == endField) {
              ""
            } else {
              val substrStart = startField match {
                case DT.DAY => 10
                case DT.HOUR => 13
                case DT.MINUTE => 16
              }
              val substrEnd = endField match {
                case DT.HOUR => 13
                case DT.MINUTE => 16
                case DT.SECOND => 26
              }
              baseStr.substring(substrStart, substrEnd)
            }

            s"$prefix$firstStr$followingStr$postfix"
          case HIVE_STYLE => baseStr
        }
        return minIntervalString
      } else {
        sign = "-"
        rest = -rest
      }
    }
    val intervalString = style match {
      case ANSI_STYLE =>
        val formatBuilder = new mutable.StringBuilder(sign)
        val formatArgs = new mutable.ArrayBuffer[Long]()
        startField match {
          case DT.DAY =>
            formatBuilder.append(rest / MICROS_PER_DAY)
            rest %= MICROS_PER_DAY
          case DT.HOUR =>
            formatBuilder.append("%02d")
            formatArgs.append(rest / MICROS_PER_HOUR)
            rest %= MICROS_PER_HOUR
          case DT.MINUTE =>
            formatBuilder.append("%02d")
            formatArgs.append(rest / MICROS_PER_MINUTE)
            rest %= MICROS_PER_MINUTE
          case DT.SECOND =>
            val leadZero = if (rest < 10 * MICROS_PER_SECOND) "0" else ""
            formatBuilder.append(s"$leadZero" +
              s"${java.math.BigDecimal.valueOf(rest, 6).stripTrailingZeros.toPlainString}")
        }

        if (startField < DT.HOUR && DT.HOUR <= endField) {
          formatBuilder.append(" %02d")
          formatArgs.append(rest / MICROS_PER_HOUR)
          rest %= MICROS_PER_HOUR
        }
        if (startField < DT.MINUTE && DT.MINUTE <= endField) {
          formatBuilder.append(":%02d")
          formatArgs.append(rest / MICROS_PER_MINUTE)
          rest %= MICROS_PER_MINUTE
        }
        if (startField < DT.SECOND && DT.SECOND <= endField) {
          val leadZero = if (rest < 10 * MICROS_PER_SECOND) "0" else ""
          formatBuilder.append(
            s":$leadZero${java.math.BigDecimal.valueOf(rest, 6).stripTrailingZeros.toPlainString}")
        }
        s"$prefix${formatBuilder.toString.format(formatArgs.toSeq: _*)}$postfix"
      case HIVE_STYLE =>
        val secondsWithFraction = rest % MICROS_PER_MINUTE
        rest /= MICROS_PER_MINUTE
        val minutes = rest % MINUTES_PER_HOUR
        rest /= MINUTES_PER_HOUR
        val hours = rest % HOURS_PER_DAY
        val days = rest / HOURS_PER_DAY
        val seconds = secondsWithFraction / MICROS_PER_SECOND
        val nanos = (secondsWithFraction % MICROS_PER_SECOND) * NANOS_PER_MICROS
        f"$sign$days $hours%02d:$minutes%02d:$seconds%02d.$nanos%09d"
    }
    intervalString
  }

  def intToYearMonthInterval(v: Int, startField: Byte, endField: Byte): Int = {
    endField match {
      case YEAR =>
        try {
          Math.multiplyExact(v, MONTHS_PER_YEAR)
        } catch {
          case _: ArithmeticException =>
            throw QueryExecutionErrors.castingCauseOverflowError(
              v,
              IntegerType,
              YearMonthIntervalType(startField, endField))
        }
      case MONTH => v
    }
  }

  def longToYearMonthInterval(v: Long, startField: Byte, endField: Byte): Int = {
    val vInt = v.toInt
    if (v != vInt) {
      throw QueryExecutionErrors.castingCauseOverflowError(
        v,
        LongType,
        YearMonthIntervalType(startField, endField))
    }
    intToYearMonthInterval(vInt, startField, endField)
  }

  def decimalToYearMonthInterval(
      d: Decimal, p: Int, s: Int, startField: Byte, endField: Byte): Int = {
    try {
      val months = if (endField == YEAR) d.toBigDecimal * MONTHS_PER_YEAR else d.toBigDecimal
      months.setScale(0, BigDecimal.RoundingMode.HALF_UP).toIntExact
    } catch {
      case _: ArithmeticException =>
        throw QueryExecutionErrors.castingCauseOverflowError(
          d,
          DecimalType(p, s),
          YearMonthIntervalType(startField, endField))
    }
  }

  def yearMonthIntervalToInt(v: Int, startField: Byte, endField: Byte): Int = {
    endField match {
      case YEAR => v / MONTHS_PER_YEAR
      case MONTH => v
    }
  }

  def yearMonthIntervalToShort(v: Int, startField: Byte, endField: Byte): Short = {
    val vInt = yearMonthIntervalToInt(v, startField, endField)
    val vShort = vInt.toShort
    if (vInt != vShort) {
      throw QueryExecutionErrors.castingCauseOverflowError(
        v,
        YearMonthIntervalType(startField, endField),
        ShortType)
    }
    vShort
  }

  def yearMonthIntervalToByte(v: Int, startField: Byte, endField: Byte): Byte = {
    val vInt = yearMonthIntervalToInt(v, startField, endField)
    val vByte = vInt.toByte
    if (vInt != vByte) {
      throw QueryExecutionErrors.castingCauseOverflowError(
        v,
        YearMonthIntervalType(startField, endField),
        ByteType)
    }
    vByte
  }

  def intToDayTimeInterval(v: Int, startField: Byte, endField: Byte): Long = {
    endField match {
      case DAY =>
        try {
          Math.multiplyExact(v, MICROS_PER_DAY)
        } catch {
          case _: ArithmeticException =>
            throw QueryExecutionErrors.castingCauseOverflowError(
              v,
              IntegerType,
              DayTimeIntervalType(startField, endField))
        }
      case HOUR => v * MICROS_PER_HOUR
      case MINUTE => v * MICROS_PER_MINUTE
      case SECOND => v * MICROS_PER_SECOND
    }
  }

  def longToDayTimeInterval(v: Long, startField: Byte, endField: Byte): Long = {
    try {
      endField match {
        case DAY => Math.multiplyExact(v, MICROS_PER_DAY)
        case HOUR => Math.multiplyExact(v, MICROS_PER_HOUR)
        case MINUTE => Math.multiplyExact(v, MICROS_PER_MINUTE)
        case SECOND => Math.multiplyExact(v, MICROS_PER_SECOND)
      }
    } catch {
      case _: ArithmeticException =>
        throw QueryExecutionErrors.castingCauseOverflowError(
          v,
          LongType,
          DayTimeIntervalType(startField, endField))
    }
  }

  def dayTimeIntervalToLong(v: Long, startField: Byte, endField: Byte): Long = {
    endField match {
      case DAY => v / MICROS_PER_DAY
      case HOUR => v / MICROS_PER_HOUR
      case MINUTE => v / MICROS_PER_MINUTE
      case SECOND => v / MICROS_PER_SECOND
    }
  }

  def dayTimeIntervalToDecimal(v: Long, endField: Byte): Decimal = {
    endField match {
      case DAY => Decimal(v / MICROS_PER_DAY)
      case HOUR => Decimal(v / MICROS_PER_HOUR)
      case MINUTE => Decimal(v / MICROS_PER_MINUTE)
      case SECOND => Decimal(v, Decimal.MAX_LONG_DIGITS, 6)
    }
  }

  def decimalToDayTimeInterval(
      d: Decimal, p: Int, s: Int, startField: Byte, endField: Byte): Long = {
    try {
      val micros = endField match {
        case DAY => d.toBigDecimal * MICROS_PER_DAY
        case HOUR => d.toBigDecimal * MICROS_PER_HOUR
        case MINUTE => d.toBigDecimal * MICROS_PER_MINUTE
        case SECOND => d.toBigDecimal * MICROS_PER_SECOND
      }
      micros.setScale(0, BigDecimal.RoundingMode.HALF_UP).toLongExact
    } catch {
      case _: ArithmeticException =>
        throw QueryExecutionErrors.castingCauseOverflowError(
          d, DecimalType(p, s), DT(startField, endField))
    }
  }

  def dayTimeIntervalToInt(v: Long, startField: Byte, endField: Byte): Int = {
    val vLong = dayTimeIntervalToLong(v, startField, endField)
    val vInt = vLong.toInt
    if (vLong != vInt) {
      throw QueryExecutionErrors.castingCauseOverflowError(
        v,
        DayTimeIntervalType(startField, endField),
        IntegerType)
    }
    vInt
  }

  def dayTimeIntervalToShort(v: Long, startField: Byte, endField: Byte): Short = {
    val vLong = dayTimeIntervalToLong(v, startField, endField)
    val vShort = vLong.toShort
    if (vLong != vShort) {
      throw QueryExecutionErrors.castingCauseOverflowError(
        v,
        DayTimeIntervalType(startField, endField),
        ShortType)
    }
    vShort
  }

  def dayTimeIntervalToByte(v: Long, startField: Byte, endField: Byte): Byte = {
    val vLong = dayTimeIntervalToLong(v, startField, endField)
    val vByte = vLong.toByte
    if (vLong != vByte) {
      throw QueryExecutionErrors.castingCauseOverflowError(
        v,
        DayTimeIntervalType(startField, endField),
        ByteType)
    }
    vByte
  }
}
