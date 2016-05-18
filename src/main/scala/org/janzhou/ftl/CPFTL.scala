package org.janzhou.ftl

import akka.actor.{ActorSystem, Props}
import akka.actor.{ActorLogging, Actor, ActorRef}
import akka.pattern.ask

import scala.collection.JavaConverters._

import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashMap

import org.janzhou.cminer._

class CPFTL(
  val device:Device,
  val miner:Miner = new CMiner(),
  val accessSequenceLength:Int = 4096
) extends DFTL(device) {

  println("CPFTL")

  private val MineSystem = ActorSystem("Miner")
  private val mineActor = MineSystem.actorOf(Props(new MineActor()))
  private val prefetchActor = MineSystem.actorOf(Props(new PrefetchActor()))

  override def read(lpn:Int):Int = {
    if ( dftl_table(lpn).cached == false ) {
      Static.cacheMiss
    } else {
      Static.cacheHit
    }

    Static.prefetchStart
    prefetchActor ! NewAccess(lpn)
    Static.prefetchStop

    realRead(lpn)
  }

  case class NewAccess(lpn:Int)
  case class NewSequence(seq:List[Int])
  case class NewCorrelations(correlations: HashMap[Int, List[Int]])

  class PrefetchActor extends Actor with ActorLogging {
    private var accessSequence = ArrayBuffer[Int]()
    private var correlations = HashMap[Int, List[Int]]()

    private def prefetch(lpn:Int) = {
      if ( dftl_table(lpn).cached == false ) {
        if ( correlations contains lpn ) {
          correlations(lpn).foreach(lpn => {
            cache(lpn)
          })
        }
      }
    }

    def receive = {
      case NewAccess(lpn) => {
        prefetch(lpn)
        mineActor ! NewAccess(lpn)
      }
      case NewCorrelations(tmp_correlations) => {
        correlations = tmp_correlations
      }
    }
  }

  class MineActor extends Actor with ActorLogging {
    private var accessSequence = ArrayBuffer[Int]()

    def receive = {
      case NewAccess(lpn) => {
        accessSequence = accessSequence :+ lpn
        if ( accessSequence.length >= accessSequenceLength ) {
          val tmp_accessSequence = accessSequence
          accessSequence = ArrayBuffer[Int]()
          self ! NewSequence(tmp_accessSequence.toList)
        }
      }
      case NewSequence(tmp_accessSequence) => {
        Static.miningStart(tmp_accessSequence)
        val tmp_correlations = miningFrequentSubSequence(tmp_accessSequence)
        Static.miningStop(tmp_correlations)

        var correlations = HashMap[Int, List[Int]]()
        tmp_correlations.foreach(seq => {
          seq.foreach(lba => {
            if( correlations contains lba ) {
              val new_seq = seq ::: correlations(lba)
              correlations += lba -> new_seq
            } else {
              correlations += lba -> seq
            }
          })
        })

        prefetchActor ! NewCorrelations(correlations.map{
          case (k, v) => (k, v.distinct)
        })
      }
    }


    private def miningFrequentSubSequence (accessSequence:List[Int]):List[List[Int]] = {
      miner.mine(accessSequence.toList)
    }
  }
}
