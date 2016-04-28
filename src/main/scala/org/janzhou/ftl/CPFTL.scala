package org.janzhou.ftl

import java.util.concurrent.TimeUnit
import orestes.bloomfilter._
import scala.collection.JavaConverters._
import java.util.concurrent.Semaphore

class CPFTL(device:Device) extends DFTL(device) with Runnable {

  println("CPFTL")

  private var accessSequence = List[Int]()
  private var correlations = List[List[Int]]()
  private var bfs = List[BloomFilter[Int]]()
  private var bf:BloomFilter[Int] = new FilterBuilder(0, 0.01).buildBloomFilter()

  private val do_mining = new Semaphore(0);

  def prefetch(lpn:Int) = {
    val tmp = this.synchronized{
      (correlations, bfs, bf)
    }

    val tmp_correlations = tmp._1
    val tmp_bfs = tmp._2
    val tmp_bf = tmp._3

    if ( !dftl_table(lpn).cached ) {
      if ( tmp_bf.contains(lpn) ) {
        for ( i <- 0 to tmp_bfs.length - 1 ) {
          val bf = tmp_bfs(i)
          if ( bf.contains(lpn) ) {
            correlations(i).foreach( lpn => cache(lpn) )
          }
        }
        cache(lpn)
      }
    }
  }

  override def read(lpn:Int):Int = {
    val sequence_length = this.synchronized{
      accessSequence = accessSequence :+ lpn
      accessSequence.length
    }

    if ( sequence_length >= 512 ) {
      if ( do_mining.availablePermits == 0 ) do_mining.release
    }

    prefetch(lpn)
    super.read(lpn)
  }

  private def miningFrequentSubSequence (accessSequence:List[Int]):List[List[Int]] = {
    accessSequence.grouped(64).toList
  }

  override def run = {

    do_mining.acquire

    val tmp_accessSequence = this.synchronized {
      val sequence = accessSequence
      accessSequence = List[Int]()
      sequence
    }

    val tmp_correlations = miningFrequentSubSequence(tmp_accessSequence)

    val tmp_bfs = tmp_correlations.map(seq => {
      val bf:BloomFilter[Int] = new FilterBuilder(seq.length, 0.01).buildBloomFilter()
      bf.addAll(seq.asJava)
      bf

    })

    val full = tmp_correlations.reduce( _ ::: _ )
    val tmp_bf:BloomFilter[Int] = new FilterBuilder(full.length, 0.01).buildBloomFilter()
    tmp_bf.addAll(full.asJava)

    this.synchronized{
      correlations = tmp_correlations
      bfs = tmp_bfs
      bf = tmp_bf
    }

  }

  val thread = new Thread(this)
  thread.start()

}