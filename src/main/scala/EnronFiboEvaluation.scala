import org.apache.spark.graphx._
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}

import scala.collection.mutable.ListBuffer

/**
  * Created by frank on 07/07/16.
  *
  * Not working too long

  */
object EnronFiboEvaluation extends App{


  // New SparkContext
  val sc = new SparkContext(new SparkConf()
    // local 8 means local machine 8 threads, optimal with 8 core CPU
    // yarn-local : on the cluster with local machine as master and default output terminal
    // yarn-cluster : on the cluster with best setup but no output on terminal, must look at logs of each machine
    .setMaster("local[8]")
    .setAppName("EnronFibo")
  )

  val mailDataset = sc.wholeTextFiles("hdfs://master.spark.com/Enron/maildir/*/_sent_mail/*").map(_._2)
  val Array(sentMails,testSet) = mailDataset.randomSplit(Array(0.7,0.3))
  val users = new ListBuffer[String]
  val fromUsers = new ListBuffer[String]
  val anonymousGroup = new ListBuffer[String]


  // RFC Standard
  val mailPattern = "(?:[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*|\"(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21\\x23-\\x5b\\x5d-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])*\")@(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?|\\[(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?|[a-z0-9-]*[a-z0-9]:(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21-\\x5a\\x53-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])+)\\])".r

  val listEdges = new ListBuffer[(Int, Int, String)]

  sentMails.collect().foreach(f = mail => {
    val toLine = mail.split("\n").filter(line => line.contains("To: ")).head
    val ccLine = mail.split("\n").filter(line => line.contains("cc: ")).head
    val fromLine = mail.split("\n").filter(line => line.contains("From: ")).head
    val from: String = (mailPattern findFirstIn fromLine).get
    if (!users.contains(from)) {
      users.append(from)
    }
    if (!fromUsers.contains(from)) {
      fromUsers.append(from)
    }
    // First let's take care of the To section
    // We add users to the list if they are not already in
    val toArray: Array[String] = (mailPattern findAllIn toLine).toArray.map(toUser => {
      if (!users.contains(toUser)) {
        users.append(toUser)
      }
      toUser
    })
    // If there is just 1 to, make a direct link between from and to
    if (toArray.length == 1) {
      for (to <- toArray) {
        listEdges.append((users.indexOf(from), users.indexOf(to), "to"))
      }
    }
    // else create an anonymous node and make a link from-node, node-tos
    else if(toArray.length>1){
      // TODO : Add from in toArray: OK
      val toArrayIntSorted:String = (toArray.map(users.indexOf(_))).sortWith(_ < _).mkString("")
      if (!anonymousGroup.contains(toArrayIntSorted)){
        anonymousGroup.append(toArrayIntSorted)
      }
      listEdges.append((users.indexOf(from),anonymousGroup.indexOf(toArrayIntSorted)+10000,"to"))
      for (to <- toArray) {
        listEdges.append((anonymousGroup.indexOf(toArrayIntSorted)+10000, users.indexOf(to), "to"))
      }
    }
    // We take care of the CC section
    val ccArray: Array[String] = (mailPattern findAllIn ccLine).toArray.map(ccUser => {
      if (!users.contains(ccUser)) {
        users.append(ccUser)
      }
      ccUser
    })
    // If there is just 1 to, make a direct link between from and cc
    if (ccArray.length==1) {
      for (cc <- ccArray) {
        listEdges.append((users.indexOf(from), users.indexOf(cc), "cc"))
      }
    }
    // else create an anonymous node and make a link from-node, node-ccs
    else if (ccArray.length > 1) {
      // TODO : Add from in ccArray
      val ccArrayIntSorted = (ccArray.map(users.indexOf(_))).sortWith(_ < _).mkString("")
      if (!anonymousGroup.contains(ccArrayIntSorted)){
        anonymousGroup.append(ccArrayIntSorted)
      }
      listEdges.append((users.indexOf(from),anonymousGroup.indexOf(ccArrayIntSorted)+10000,"to"))
      for (cc <- ccArray) {
        listEdges.append((anonymousGroup.indexOf(ccArrayIntSorted)+10000, users.indexOf(cc), "to"))
      }
    }
  })

  val tripleRDD : RDD[(Int,Int,String)] = sc.parallelize(listEdges)

  // Replace arc string by count we use 1 to get shortest path to
  val triplesArcCountRDD:RDD[(Int,Int,Int)] = tripleRDD.map(triple =>(triple._1+""+triple._2,(triple._1,triple._2,1))).reduceByKey((triple1,triple2)=>(triple1._1,triple1._2,triple1._3+triple2._3)).map(_._2)

  def fibonacci(elem:Int):Int={
    if(elem==0){return 1}
    if(elem==1){return 1}
    else{return (fibonacci(elem-1)+fibonacci(elem-2))}
  }

  // Replace cardinal by Fibonacci suite element
  val triplesFiboRDD = triplesArcCountRDD.map(triple => (triple._1,triple._2,fibonacci(triple._3)))

  val userArray :Array[(Long,(String))]= users.toArray.map(mail => (users.indexOf(mail).toLong,(users(users.indexOf(mail)))))
  val usersRDD: RDD[(VertexId, (String))] = sc.parallelize(userArray.toSeq)

  //Create Triples Edges
  val edgesRDD = triplesFiboRDD.map(triple => Edge(triple._1,triple._2.hashCode,-triple._3))

  // Create the Graph
  val graph = Graph(usersRDD,edgesRDD, "defaultProperty")

  var correctReco = 0
  var totalGroupMail = 0

  testSet.collect().foreach({mail =>
    val fromLine = mail.split("\n").filter(line => line.contains("From: ")).head
    val from: String = (mailPattern findFirstIn fromLine).get
    val toLine = mail.split("\n").filter(line => line.contains("To: ")).head
    val ccLine = mail.split("\n").filter(line => line.contains("cc: ")).head
    val toArray: Array[String] = (mailPattern findAllIn toLine).toArray
    // TODO : Add from in toArray for toArrayIntSorted
    val toArrayIntSorted = (toArray.map(users.indexOf(_))).sortWith(_ < _).mkString("")
    val ccArray: Array[String] = (mailPattern findAllIn ccLine).toArray
    // TODO : Add from in ccArray for ccArrayIntSorted
    val ccArrayIntSorted = (ccArray.map(users.indexOf(_))).sortWith(_ < _).mkString("")
    if (toArray.length > 1) {
      val to = toArray.head
      val destid = users.indexOf(to)
      val senderId = users.indexOf(from)
      val tableGraph = graph.edges
        // Select the user dest user and the source user
        .filter(row => (row.dstId == destid && (row.srcId == senderId || row.srcId > 9999)))
        // Sort recipient users by number of exchange
        .sortBy(_.attr)
      if (tableGraph.count() > 1) {
        val id = tableGraph
          .first().srcId

        val annonymousUserArray = graph.edges
          .filter(_.srcId == id).map(_.dstId).collect()
        if (id > 9999) {
          val recommendedUserArray = graph.edges
            .filter(_.srcId == id).map(_.dstId).collect()
          for (to <- toArray){
            if (recommendedUserArray.contains(users.indexOf(to))){
              correctReco+=1
            }
          }
          totalGroupMail+=toArray.length
        }
      }
      if (ccArray.length > 1) {
        val cc = ccArray.head
        val destid = users.indexOf(cc)
        val senderId = users.indexOf(from)
        val tableGraph = graph.edges
          // Select the user dest user and the source user
          .filter(row => (row.dstId == destid && (row.srcId == senderId || row.srcId > 9999)))
          // Sort recipient users by number of exchange
          .sortBy(_.attr)
        if (tableGraph.count() > 0) {
          val id = tableGraph
            .first().srcId

          val annonymousUserArray = graph.edges
            .filter(_.srcId == id).map(_.dstId).collect()
          if (id > 9999) {
            val recommendedUserArray = graph.edges
              .filter(_.srcId == id).map(_.dstId).collect()
            for (cc <- ccArray){
              if (recommendedUserArray.contains(users.indexOf(cc))){
                correctReco+=1
              }
            }
            totalGroupMail+=ccArray.length
          }
        }
      }
    }
  })
  val testSetSize= testSet.count()
  println("\n Accuracy of the recommender using fibonacci suite : "+(correctReco.toLong/testSetSize.toLong)+" with : "+correctReco+" correct guesses on "+totalGroupMail+" recipients in test set")

  //printings
  sc.stop()
}
