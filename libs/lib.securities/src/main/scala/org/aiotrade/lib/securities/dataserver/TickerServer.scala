/*
 * Copyright (c) 2006-2007, AIOTrade Computing Co. and Contributors
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without 
 * modification, are permitted provided that the following conditions are met:
 * 
 *  o Redistributions of source code must retain the above copyright notice, 
 *    this list of conditions and the following disclaimer. 
 *    
 *  o Redistributions in binary form must reproduce the above copyright notice, 
 *    this list of conditions and the following disclaimer in the documentation 
 *    and/or other materials provided with the distribution. 
 *    
 *  o Neither the name of AIOTrade Computing Co. nor the names of 
 *    its contributors may be used to endorse or promote products derived 
 *    from this software without specific prior written permission. 
 *    
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" 
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, 
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR 
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR 
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, 
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, 
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; 
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, 
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR 
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, 
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.aiotrade.lib.securities.dataserver

import java.util.logging.Logger
import org.aiotrade.lib.math.timeseries.TFreq
import org.aiotrade.lib.math.timeseries.TSerEvent
import org.aiotrade.lib.math.timeseries.datasource.DataServer
import org.aiotrade.lib.securities.TickerSnapshot
import org.aiotrade.lib.securities.model.Tickers
import org.aiotrade.lib.securities.model.Exchange
import org.aiotrade.lib.securities.model.Execution
import org.aiotrade.lib.securities.model.ExecutionEvent
import org.aiotrade.lib.securities.model.Executions
import org.aiotrade.lib.securities.model.LightTicker
import org.aiotrade.lib.securities.model.MarketDepth
import org.aiotrade.lib.securities.model.Quote
import org.aiotrade.lib.securities.model.Quotes1m
import org.aiotrade.lib.securities.model.Sec
import org.aiotrade.lib.securities.model.SecSnap
import org.aiotrade.lib.securities.model.Ticker
import org.aiotrade.lib.securities.model.TickersLast
import org.aiotrade.lib.util.actors.Event
import org.aiotrade.lib.util.actors.Publisher
import org.aiotrade.lib.collection.ArrayList
import ru.circumflex.orm._
import scala.collection.mutable.HashMap

/**
 *
 * @author Caoyuan Deng
 */
case class TickerEvent(ticker: Ticker) extends Event // TickerEvent only accept Ticker
case class TickersEvent(tickers: Array[LightTicker]) extends Event // TickersEvent accept LightTicker

case class DepthSnap (
  prevPrice: Double,
  prevDepth: MarketDepth,
  execution: Execution
)

object TickerServer extends Publisher
abstract class TickerServer extends DataServer[Ticker] {
  type C = TickerContract

  private val log = Logger.getLogger(this.getClass.getName)

  refreshable = true

  def tickerSnapshotOf(uniSymbol: String): TickerSnapshot = {
    val sec = Exchange.secOf(uniSymbol).get
    sec.tickerSnapshot
  }

  override def subscribe(contract: TickerContract) {
    super.subscribe(contract)
    /**
     * !NOTICE
     * the symbol-tickerSnapshot pair must be immutable, other wise, if
     * the symbol is subscribed repeatly by outside, the old observers
     * of tickerSnapshot may not know there is a new tickerSnapshot for
     * this symbol, the older one won't be updated any more.
     */
    val symbol = contract.srcSymbol
    val sec = Exchange.secOf(symbol).get
    val tickerSnapshot = sec.tickerSnapshot
    tickerSnapshot.symbol = symbol
  }

  override def unSubscribe(contract: TickerContract) {
    super.unSubscribe(contract)
    val symbol = contract.srcSymbol
    val sec = Exchange.secOf(symbol).get
    val tickerSnapshot = sec.tickerSnapshot
  }

  override protected def postLoadHistory(values: Array[Ticker]): Long = {
    val events = composeSer(values)
    var lastTime = Long.MinValue
    events foreach {
      case TSerEvent.ToBeSet(source, symbol, fromTime, toTime, lastObject, callback) =>
        source.publish(TSerEvent.FinishedLoading(source, symbol, fromTime, toTime, lastObject, callback))
        log.info(symbol + ": " + count + ", data loaded, load server finished")
        lastTime = toTime
      case _ =>
    }
    lastTime
  }

  override protected def postRefresh(values: Array[Ticker]): Long = {
    val events = composeSer(values)
    var lastTime = Long.MinValue
    events foreach {
      case TSerEvent.ToBeSet(source, symbol, fromTime, toTime, lastObject, callback) =>
        source.publish(TSerEvent.Updated(source, symbol, fromTime, toTime, lastObject, callback))
        lastTime = toTime
      case _ =>
    }
    lastTime
  }

  override protected def postStopRefresh {}

  private val allTickers = new ArrayList[Ticker]
  private val allExecutions = new ArrayList[Execution]
  private val allDepthSnaps = new ArrayList[DepthSnap]
  private val updatedDailyQuotes = new ArrayList[Quote]

  private val tickersLastToUpdate = new ArrayList[Ticker]
  private val tickersLastToInsert = new ArrayList[Ticker]

  private val exchangeToLastTime = new HashMap[Exchange, Long]

  private val updatedEvents = new ArrayList[TSerEvent]

  private def getSecSnaps(values: Array[Ticker]): Seq[SecSnap] = {
    tickersLastToUpdate.clear
    tickersLastToInsert.clear

    val secSnaps = new ArrayList[SecSnap]
    var i = 0
    while (i < values.length) {
      val ticker = values(i)

      val symbol = ticker.symbol
      if (subscribedSrcSymbols.contains(symbol) && (ticker.dayHigh != 0 && ticker.dayLow != 0)) {
        val sec = Exchange.secOf(symbol).get
        val exchange = sec.exchange

        ticker.sec = sec
        val (tickerx, transient) = exchange.gotLastTicker(ticker)
        if (transient) {
          tickersLastToInsert += tickerx
        } else {
          tickersLastToUpdate += tickerx
        }

        val contract = subscribedSrcSymbols.get(symbol).get

        val secSnap = sec.secSnap.setByTicker(ticker)
        secSnaps += secSnap
      }

      i += 1
    }
    secSnaps
  }

  /**
   * compose ser using data from Tickers
   * @param Tickers
   */
  def composeSer(values: Array[Ticker]): Seq[TSerEvent] = {
    log.info("Composing ser from tickers: " + values.length)
    if (values.length == 0) return Nil

    val secSnaps = getSecSnaps(values)
    if (secSnaps.length == 0) return Nil

    allTickers.clear
    allExecutions.clear
    allDepthSnaps.clear
    updatedDailyQuotes.clear

    exchangeToLastTime.clear
    updatedEvents.clear

    var i = 0
    while (i < secSnaps.length) {
      val secSnap = secSnaps(i)

      val sec = secSnap.sec
      val isDayFirst = secSnap.isDayFirstTicker
      val ticker = secSnap.currTicker
      val prevTicker = secSnap.prevTicker
      val updateInfo = secSnap.updateInfo
      val dayQuote = secSnap.dailyQuote
      val minQuote = secSnap.minuteQuote

      var tickerValid = false
      var execution: Execution = null
      if (isDayFirst) {
        secSnap.dailyQuote.unjustOpen_!

        tickerValid = true

        /**
         * this is today's first ticker we got when begin update data server,
         * actually it should be, so maybe we should check this.
         * As this is the first data of today:
         * 1. set OHLC = Ticker.LAST_PRICE
         * 2. to avoid too big volume that comparing to following dataSeries.
         * so give it a small 0.0001 (if give it a 0, it will won't be calculated
         * in calcMaxMin() of ChartView)
         */
        execution = new Execution
        execution.sec = sec
        execution.time = ticker.time
        execution.price  = ticker.lastPrice
        execution.volume = ticker.dayVolume
        execution.amount = ticker.dayAmount
        allExecutions += execution

        minQuote.open   = ticker.lastPrice
        minQuote.high   = ticker.lastPrice
        minQuote.low    = ticker.lastPrice
        minQuote.close  = ticker.lastPrice
        minQuote.volume = 0.00001
        minQuote.amount = 0.00001

      } else {

        /**
         *    ticker.time    prevTicker.time
         *          |------------------|------------------->
         *          |<----- 1000 ----->|
         */
        if (ticker.time + 1000 > prevTicker.time) { // 1000ms, @Note: we may add +1 to ticker.time later
          // some datasources only count on second, but we may truly have a new ticker
          if (ticker.time <= prevTicker.time) {
            ticker.time = prevTicker.time + 1 // avoid duplicate key
          }

          tickerValid = true

          if (ticker.dayVolume > prevTicker.dayVolume) {
            execution = new Execution
            execution.sec = sec
            execution.time = ticker.time
            execution.price  = ticker.lastPrice
            execution.volume = ticker.dayVolume - prevTicker.dayVolume
            execution.amount = ticker.dayAmount - prevTicker.dayAmount
            allExecutions += execution
          }

          if (minQuote.justOpen_?) {
            minQuote.unjustOpen_!

            minQuote.open  = ticker.lastPrice
            minQuote.high  = ticker.lastPrice
            minQuote.low   = ticker.lastPrice
            minQuote.close = ticker.lastPrice

          } else {

            if (prevTicker.dayHigh != 0 && ticker.dayHigh != 0) {
              if (ticker.dayHigh > prevTicker.dayHigh) {
                /** this is a new day high happened during this ticker */
                minQuote.high = ticker.dayHigh
              }
            }
            if (ticker.lastPrice != 0) {
              minQuote.high = math.max(minQuote.high, ticker.lastPrice)
            }

            if (prevTicker.dayLow != 0 && ticker.dayLow != 0) {
              if (ticker.dayLow < prevTicker.dayLow) {
                /** this is a new day low happened during this ticker */
                minQuote.low = ticker.dayLow
              }
            }
            if (ticker.lastPrice != 0) {
              minQuote.low = math.min(minQuote.low, ticker.lastPrice)
            }

            minQuote.close = ticker.lastPrice
            if (execution != null && execution.volume > 1) {
              minQuote.volume += execution.volume
              minQuote.amount += execution.amount
            }
          }
        } else {
          log.warning("Discard ticker " + ticker.toString)
        }
      }


      if (execution != null) {
        val prevPrice = if (isDayFirst) ticker.prevClose else prevTicker.lastPrice
        val prevDepth = if (isDayFirst) MarketDepth.Empty else MarketDepth(prevTicker.bidAsks, copy = true)
        allDepthSnaps += DepthSnap(prevPrice, prevDepth, execution)

        sec.publish(ExecutionEvent(ticker.prevClose, execution))
      }

      if (tickerValid) {
        allTickers += ticker
        prevTicker.copyFrom(ticker)
        sec.publish(TickerEvent(ticker))

        // update daily quote and ser
        updateDailyQuoteByTicker(dayQuote, ticker)

        // update chainSers
        val rtSer = sec.realtimeSer
        if (rtSer.isLoaded) {
          rtSer.updateFrom(minQuote) // update realtime quoteSer from minute quote
          updateInfo.updatedSers ::= rtSer
        }
        sec.serOf(TFreq.DAILY) match {
          case Some(x) /* if x.isLoaded */ =>
            x.updateFrom(dayQuote)
            updateInfo.updatedSers ::= x
          case _ =>
        }
        sec.serOf(TFreq.ONE_MIN) match {
          case Some(x) /* if x.isLoaded */ =>
            x.updateFrom(minQuote)
            updateInfo.updatedSers ::= x
          case _ =>
        }

        exchangeToLastTime.put(sec.exchange, ticker.time)

        for (updatedSer <- updateInfo.updatedSers) {
          updatedEvents += TSerEvent.ToBeSet(updatedSer, ticker.symbol, updateInfo.frTime, updateInfo.toTime, ticker)
        }
      }

      i += 1
    }
    
    /* else {

     /**
      * no new ticker got, but should consider if it's necessary to to update quoteSer
      * as the quote window may be just opened.
      */
     sec.lastData.prevTicker match {
     case null =>
     case ticker =>
     if (ticker != null && ticker.dayHigh != 0 && ticker.dayLow != 0) {
     val dayQuote = sec.dailyQuoteOf(ticker.time)
     updateDailyQuote(dayQuote, ticker)
     // update chainSers
     sec.serOf(TFreq.DAILY) match {
     case Some(x) if x.loaded => x.updateFrom(dayQuote)
     case _ =>
     }
     sec.serOf(TFreq.ONE_MIN) match {
     case Some(x) if x.loaded => x.updateFrom(minQuote)
     case _ =>
     }
     }
     }
     } */

    // batch save to db

    var willCommit = false
    val tickers = allTickers.toArray
    if (tickers.length > 0) {
      Tickers.insertBatch_!(tickers)
      willCommit = true
    }

    val executions = allExecutions.toArray
    if (executions.length > 0) {
      Executions.insertBatch_!(executions)
      willCommit = true
    }

    val (minuteQuotesToInsert, minuteQuotesToUpdate) = Sec.minuteQuotesToClose.partition(_.transient)
    Sec.minuteQuotesToClose.clear
    if (minuteQuotesToInsert.length > 0) {
      Quotes1m.insertBatch_!(minuteQuotesToInsert.toArray)
      willCommit = true
    }
    if (minuteQuotesToUpdate.length > 0) {
      Quotes1m.updateBatch_!(minuteQuotesToUpdate.toArray)
      willCommit = true
    }

    if (tickersLastToInsert.length > 0) {
      TickersLast.insertBatch_!(tickersLastToInsert.toArray)
      willCommit = true
    }
    if (tickersLastToUpdate.length > 0) {
      TickersLast.updateBatch_!(tickersLastToUpdate.toArray)
      willCommit = true
    }

    // @Note if there is no update/insert on db, do not call commit, which may cause deadlock
    if (willCommit) {
      log.info("Committing: tickers=" + tickers.length + ", executions=" + executions.length +
               ", minuteQuotesToInsert=" + minuteQuotesToInsert.length +
               ", minuteQuotesToUpdate=" + minuteQuotesToUpdate.length
      )
      commit
      log.info("Committed")
    }
    
    val depthSnaps = allDepthSnaps.toArray
    if (depthSnaps.length > 0) {
      processDepthSnaps(depthSnaps)
    }

    for ((exchange, lastTime) <- exchangeToLastTime) {
      val status = exchange.tradingStatusOf(lastTime)
      log.info("Trading status of " + exchange + ": " + status)
      exchange.tradingStatus = status
    }

    // publish events
    if (tickers.length > 0) {
      TickerServer.publish(TickersEvent(tickers.asInstanceOf[Array[LightTicker]]))
    }

    updatedEvents
  }

  protected def processDepthSnaps(depthSnaps: Array[DepthSnap]) = ()

  private def updateDailyQuoteByTicker(dailyQuote: Quote, ticker: Ticker) {
    dailyQuote.open   = ticker.dayOpen
    dailyQuote.high   = ticker.dayHigh
    dailyQuote.low    = ticker.dayLow
    dailyQuote.close  = ticker.lastPrice
    dailyQuote.volume = ticker.dayVolume
    dailyQuote.amount = ticker.dayAmount
  }

  def toSrcSymbol(uniSymbol: String): String = uniSymbol
  def toUniSymbol(srcSymbol: String): String = srcSymbol
}
