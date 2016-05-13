
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}

import scala.collection.mutable.ListBuffer

/**
  * Created by frank
  */
object EnronMatrixCreation extends App{

  val sc = new SparkContext(new SparkConf()
    .setMaster("local[2]")
    .setAppName("EnronMatrixCreation")
  )

  type EnronRow = (Int,Int,Int)


  val EnronRDD : RDD[EnronRow] = sc.textFile("hdfs://master.spark.com/Enron/TimeFromToDataset/execs.email.linesnum")
    .map(line => {
      val lineArray =  line.split(" ")
      (lineArray(0).toInt,lineArray(1).toInt,lineArray(2).toInt)
    })

  // Mails sent TO user
  val EnronReceivedMailRDD: RDD[(Int,Iterable[EnronRow])] = EnronRDD.groupBy(_._3)
  //mails sent To user 25
  val userReceivedMail=  EnronReceivedMailRDD.collect().filter(_._1==25).head._2.toArray

  //mails SENT BY (FROM) user
  val EnronSentMailRDD : RDD[(Int,Iterable[EnronRow])]= EnronRDD.groupBy(_._2)
  // mails SENT By (FROM) user 25
  val userSentMails: Array[EnronRow] = EnronSentMailRDD.collect().filter(_._1==25).head._2.toArray


  //create one row between each mail dent by the user
  val matrix : ListBuffer[Array[Int]] = new ListBuffer[Array[Int]]()
  var index = 0


  val row: Array[Int] = Array.fill[Int](185)(0)

    for (receivedMail <- userReceivedMail ){
      //userSentMails.foreach( sentMail => {

    val receivedmailTime = receivedMail._1
    if (index <= userReceivedMail.size ) {
      if (receivedmailTime < userSentMails(index)._1) {
        row(userReceivedMail(index)._2) += 1
        index += 1
      }
      else {
        row(userSentMails(index)._2) -= 1
        row(184) = userSentMails(index)._3
        matrix.append(row)
      }
    }
  }

  val matrixString=matrix.map(_.mkString(" , "))

  println("\n Taille de la matrice " + matrix.length + "\n")
  println("\n Taille de userRceivedMail 25 : " + userReceivedMail.size + "\n")
  println("\n Taille de toto : " + userSentMails.size + "\n")
  println("\n Matrix :\n "+ matrixString.mkString("\n"))


}
