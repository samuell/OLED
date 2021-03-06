/*
 * Copyright (C) 2016  Nikos Katzouris
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package oled.mwua

import java.io.{BufferedWriter, File, FileWriter, PrintWriter}

import akka.actor.Actor
import app.runutils.IOHandling.InputSource
import app.runutils.RunningOptions
import com.typesafe.scalalogging.Logger
import logic.Examples.Example
import logic.{Clause, Theory}
import oled.functions.SingleCoreOLEDFunctions
import org.slf4j.LoggerFactory

import scala.util.Random
import scala.util.matching.Regex

class Learner_NEW[T <: InputSource](
    val inps: RunningOptions,
    val trainingDataOptions: T,
    val testingDataOptions: T,
    val trainingDataFunction: T => Iterator[Example],
    val testingDataFunction: T => Iterator[Example],
    val writeExprmtResultsTo: String = "") extends Actor {
  val learningRate = 0.2 //1.0 //0.05 //0.2 // 1.0 usually works for winnow

  val epsilon = 0.9 //0.9 // used in the randomized version

  val randomizedPrediction = false

  val feedbackGap = 100

  // If this is false, some non-determinism is introduced (number of mistakes may vary slightly from round to round)
  val specializeAllAwakeRulesOnFPMistake = false

  val withInertia = true //false

  // This is either 'winnow' or 'hedge'
  val weightUpdateStrategy = "hedge" //"winnow" // "hedge"

  // Set this to 1.0 to simulate the case of constant feedback at each round.
  // For values < 1.0 we only update weights and structure if a biased coin
  // with receiveFeedbackBias for heads returns heads.
  val receiveFeedbackBias = 1.0 //0.09 //0.2 //0.5

  val conservativeRuleGeneration = true

  // A rule must make this much % of the total FPs before it is specialized
  val percentOfMistakesBeforeSpecialize = 0

  // have this set to "" for a regular run without an input theory
  //val inputTheoryFile = "/home/nkatz/dev/BKExamples/BK-various-taks/WeightLearning/Caviar/fragment/meeting/ASP/asp-rules-test"
  val inputTheoryFile = ""

  val inputTheory: List[Clause] = {
      def matches(p: Regex, str: String) = p.pattern.matcher(str).matches
    if (inputTheoryFile == "") {
      Nil
    } else {
      val rules = scala.io.Source.fromFile(inputTheoryFile).getLines.toList.filter(line => !matches("""""".r, line) && !line.startsWith("%"))
      val rulesParsed = rules.map(r => Clause.parse(r))
      rulesParsed
    }
  }

  val stateHandler: StateHandler = {
    val stateHandler = new StateHandler
    if (inputTheory.isEmpty) {
      stateHandler
    } else {
      val (inputInitRules, inputTermRules) = inputTheory.foldLeft(List.empty[Clause], List.empty[Clause]){ (x, y) =>
        if (y.head.functor.contains("initiated")) (x._1 :+ y, x._2) else (x._1, x._2 :+ y)
      }
      stateHandler.ensemble.initiationRules = inputInitRules
      stateHandler.ensemble.terminationRules = inputTermRules
      stateHandler
    }
  }

  // Just print-out all the data
  /*
  val test = {
    val pw = new PrintWriter(new File("/home/nkatz/Desktop/caviar-whole" ))
    data = getTrainData
    while (data.hasNext) {
      val x = data.next()
      val a = (x.annotation ++ x.narrative).mkString("\n")+"\n\n% New Batch\n\n"
      pw.write(a)
    }
    pw.close()
  }
  */

  private val logger = LoggerFactory.getLogger(self.path.name)

  private val withec = true

  // Control learning iterations over the data
  private var repeatFor = inps.repeatFor

  // Used to count examples for holdout evaluation
  private var exampleCounter = 0

  // Local data variable. Cleared at each iteration (in case repfor > 1).
  private var data = Iterator[Example]()

  // This is optional. A testing set (for holdout evaluation) may not be provided.
  private var testingData = Iterator[Example]()

  // Counts the number of processed batches. Used to determine when to
  // perform holdout evaluation on the test set. Incremented whenever a
  // new batch is fetched (see the getNextBatch() method)
  private var batchCounter = 0

  private var startTime = System.nanoTime()

  private var endTime = System.nanoTime()

  // Get the training data from the current inout source
  private def getTrainData = trainingDataFunction(trainingDataOptions)

  private def getTestingData = testingDataFunction(testingDataOptions)

  private def getNextBatch(lleNoise: Boolean = false) = {
    this.batchCounter += 1
    if (data.isEmpty) {
      Example()
    } else {
      if (!lleNoise) {
        data.next()
      } else {
        val currentBatch = data.next()
        val noisyNarrative = {
          currentBatch.narrative map { x =>
            x.replaceAll("active", "active_1")
          }
        }
        Example(annot = currentBatch.annotation, nar = noisyNarrative, _time = currentBatch.time)
      }
    }
  }

  def logEmptyDataError() = {
    if (this.data.isEmpty) {
      logger.error(s"Input source ${inps.train} is empty.")
      System.exit(-1)
    }
  }

  def wrapupAndShutDown() = {}

  def processData() = {
    data = getTrainData
    logEmptyDataError()
    var done = false

    var perBatchError: Vector[Int] = Vector.empty[Int]

    while (!done) {
      val batch = getNextBatch(lleNoise = false)
      logger.info(s"Processing batch $batchCounter")
      if (batch.isEmpty) {
        logger.info(s"Finished the data.")
        endTime = System.nanoTime()
        logger.info("Done.")
        //workers foreach(w => w ! PoisonPill)
        wrapUp()
        done = true
        //context.system.terminate()
      } else {
        val trueLabels = batch.annotation.toSet

        if (inputTheory.isEmpty) {

          //stateHandler.perBatchError = stateHandler.perBatchError :+ batchError

          /*  ======================= Dirty hack ===============================*/

          stateHandler.clearDelayedUpdates
          var bias = 0.0
          val error = ExpertAdviceFunctions.process(batch, batch.annotation.toSet, inps,
                                                    stateHandler, trueLabels, learningRate, epsilon, randomizedPrediction,
                                                    batchCounter, percentOfMistakesBeforeSpecialize, specializeAllAwakeRulesOnFPMistake,
                                                    bias, conservativeRuleGeneration, weightUpdateStrategy, withInertia, feedbackGap)

          perBatchError = perBatchError :+ error

          generateNewRules(error, batch)

          bias = 1.0
          ExpertAdviceFunctions.process(batch, batch.annotation.toSet, inps,
                                        stateHandler, trueLabels, learningRate, epsilon, randomizedPrediction,
                                        batchCounter, percentOfMistakesBeforeSpecialize, specializeAllAwakeRulesOnFPMistake,
                                        bias, conservativeRuleGeneration, weightUpdateStrategy, withInertia, feedbackGap)

          expandRules()
          woled.Utils.dumpToFile(avgLoss(perBatchError)._3.mkString(", "), "/home/nkatz/Desktop/kernel", "overwrite")

          //stateHandler.pruneRules("weight", 0.0005, Logger(this.getClass).underlying)
          //stateHandler.pruneRules("score", inps.pruneThreshold)

          /*
          stateHandler.clearDelayedUpdates
          var bias = 0.0
          val error = ExpertAdviceFunctions.process(batch, batch.annotation.toSet, inps,
            stateHandler, trueLabels, learningRate, epsilon, randomizedPrediction,
            batchCounter, percentOfMistakesBeforeSpecialize, specializeAllAwakeRulesOnFPMistake,
            bias, conservativeRuleGeneration, weightUpdateStrategy, withInertia, feedbackGap)

          perBatchError = perBatchError :+ error

          println(s"Per batch error:\n$perBatchError")
          println(s"Accumulated Per batch error:\n${perBatchError.scanLeft(0.0)(_ + _).tail}")

          //val debugDelayedUpdates = stateHandler.delayedUpdates.map(x => x.atom.atom).mkString("\n")

          stateHandler.delayedUpdates foreach { u =>
            val newRuleFlag = ExpertAdviceFunctions.updateWeights(u.atom, u.prediction, u.inertiaExpertPrediction, u.initWeightSum,
              u.termWeightSum, u.predictedLabel, u.markedMap, u.feedback, stateHandler, u.learningRate, u.weightUpdateStrategy,
              u.withInertia)
            u.generateNewRuleFlag = newRuleFlag
          }

          var newRulesFrom = stateHandler.delayedUpdates.filter(_.generateNewRuleFlag)

          /*newRulesFrom foreach { u =>
            val previousTime = u.orderedTimes( u.orderedTimes.indexOf(u.atom.time) -1 )
            ClassicSleepingExpertsHedge.updateStructure_NEW_HEDGE(u.atom, previousTime, u.markedMap, u.predictedLabel,
              u.feedback, batch, u.atom.atom, inps, Logger(this.getClass).underlying, stateHandler,
              percentOfMistakesBeforeSpecialize, randomizedPrediction, "", false,
              conservativeRuleGeneration, u.generateNewRuleFlag)
          }*/

          // Generating rules from each mistake breaks things down. Until I find a generic strategy for new rule
          // generation (applicable to WOLED also), just generate say, 3 rules, randomly.

          if (newRulesFrom.nonEmpty) {

            val random = new Random
            for (_ <- 1 to 5) {
              val u = newRulesFrom(random.nextInt(newRulesFrom.length))
              newRulesFrom = newRulesFrom.filter(x => x != u)

              val previousTime = u.orderedTimes( u.orderedTimes.indexOf(u.atom.time) -1 )

              ClassicSleepingExpertsHedge.updateStructure_NEW_HEDGE(u.atom, previousTime, u.markedMap, u.predictedLabel,
                u.feedback, batch, u.atom.atom, inps, Logger(this.getClass).underlying, stateHandler,
                percentOfMistakesBeforeSpecialize, randomizedPrediction, "", false,
                conservativeRuleGeneration, u.generateNewRuleFlag)
            }
          }

          expandRules()
          val allRules = (stateHandler.ensemble.initiationRules ++ stateHandler.ensemble.terminationRules).flatMap(x => List(x) ++ x.refinements)
          println(s"\n\n====================== Theory Size: ${allRules.size}=======================")

          woled.Utils.dumpToFile(avgLoss(perBatchError)._3.mkString(", "), "/home/nkatz/Desktop/kernel", "overwrite")
          stateHandler.pruneRules(inps.pruneThreshold)
          */

          // ACTUAL EXECUTION FLOW
          /*ExpertAdviceFunctions.process(batch, batch.annotation.toSet, inps,
            stateHandler, trueLabels, learningRate, epsilon, randomizedPrediction,
            batchCounter, percentOfMistakesBeforeSpecialize, specializeAllAwakeRulesOnFPMistake,
            receiveFeedbackBias, conservativeRuleGeneration, weightUpdateStrategy, withInertia, feedbackGap)*/

        } else {

          /*==============================*/
          stateHandler.clearDelayedUpdates
          /*==============================*/

          var bias = 1.0

          val error = ExpertAdviceFunctions.process(batch, batch.annotation.toSet, inps,
                                                    stateHandler, trueLabels, learningRate, epsilon, randomizedPrediction,
                                                    batchCounter, percentOfMistakesBeforeSpecialize, specializeAllAwakeRulesOnFPMistake,
                                                    bias, conservativeRuleGeneration, weightUpdateStrategy, withInertia, feedbackGap, inputTheory = Some(inputTheory))

          perBatchError = perBatchError :+ error

          /*val debugDelayedUpdates = stateHandler.delayedUpdates.map(x => x.atom.atom).mkString("\n")

          stateHandler.delayedUpdates foreach { u =>

            val newRuleFlag = ExpertAdviceFunctions.updateWeights(u.atom, u.prediction, u.inertiaExpertPrediction, u.initWeightSum,
              u.termWeightSum, u.predictedLabel, u.markedMap, u.feedback, stateHandler, u.learningRate, u.weightUpdateStrategy,
              u.withInertia)

            u.generateNewRuleFlag = newRuleFlag
          }*/

          /*bias = 1.0

          ExpertAdviceFunctions.process(batch, batch.annotation.toSet, inps,
            stateHandler, trueLabels, learningRate, epsilon, randomizedPrediction,
            batchCounter, percentOfMistakesBeforeSpecialize, specializeAllAwakeRulesOnFPMistake,
            bias, conservativeRuleGeneration, weightUpdateStrategy, withInertia, feedbackGap, inputTheory = Some(inputTheory))*/

          println(s"Per batch error:\n$perBatchError")
          println(s"Accumulated Per batch error:\n${perBatchError.scanLeft(0.0)(_ + _).tail}")

          /*ExpertAdviceFunctions.process(batch, batch.annotation.toSet, inps,
            stateHandler, trueLabels, learningRate, epsilon, randomizedPrediction,
            batchCounter, percentOfMistakesBeforeSpecialize, specializeAllAwakeRulesOnFPMistake,
            receiveFeedbackBias, conservativeRuleGeneration, weightUpdateStrategy, withInertia, feedbackGap, inputTheory = Some(inputTheory))*/
        }
      }
    }
  }

  def expandRules() = {
    val expandedInit =
      SingleCoreOLEDFunctions.expandRules(Theory(stateHandler.ensemble.initiationRules.filter(x => x.refinements.nonEmpty)), inps, logger)

    val expandedTerm =
      SingleCoreOLEDFunctions.expandRules(Theory(stateHandler.ensemble.terminationRules.filter(x => x.refinements.nonEmpty)), inps, logger)

    stateHandler.ensemble.initiationRules = expandedInit._1.clauses
    stateHandler.ensemble.terminationRules = expandedTerm._1.clauses
  }

  def avgLoss(in: Vector[Int]) = {
    in.foldLeft(0, 0, Vector.empty[Double]){ (x, y) =>
      val (count, prevSum, avgVector) = (x._1, x._2, x._3)
      val (newCount, newSum) = (count + 1, prevSum + y)
      (newCount, newSum, avgVector :+ newSum.toDouble / newCount)
    }
  }

  def generateNewRules(error: Double, batch: Example) = {
    if (error > 0) {
      println("Generating new rules...")
      val topInit = stateHandler.ensemble.initiationRules
      val topTerm = stateHandler.ensemble.terminationRules
      val growNewInit = Theory(topInit).growNewRuleTest(batch, "initiatedAt", inps.globals)
      val growNewTerm = Theory(topTerm).growNewRuleTest(batch, "terminatedAt", inps.globals)
      val newInit = if (growNewInit) oled.functions.SingleCoreOLEDFunctions.generateNewRules(Theory(topInit), batch, "initiatedAt", inps.globals) else Nil
      val newTerm = if (growNewTerm) oled.functions.SingleCoreOLEDFunctions.generateNewRules(Theory(topTerm), batch, "terminatedAt", inps.globals) else Nil
      stateHandler.ensemble.updateRules(newInit ++ newTerm, "add", inps)
    }
  }

  def receive = {

    case "start" => {

      for (i <- (1 to 1)) {
        processData()
      }

    }

    case "start-streaming" => {
      data = getTrainData
      val slidingData = data.sliding(20)

      var done = false
      var acuumMistakes = Vector.empty[Int]
      //while (!done) {
      while (slidingData.hasNext) {
        val dataSlice = slidingData.next()
        if (dataSlice.isEmpty) {
          logger.info(s"Finished.")
          endTime = System.nanoTime()
          logger.info("Done.")
          //workers foreach(w => w ! PoisonPill)
          wrapUp()
          done = true
          context.system.terminate()
        } else {
          // Train on the first data point of the slice, test on the rest.
          val train = dataSlice.head
          val trueLabels = train.annotation.toSet

          if (inputTheory.isEmpty) {
            ExpertAdviceFunctions.process(train, train.annotation.toSet, inps,
                                          stateHandler, trueLabels, learningRate, epsilon, randomizedPrediction,
                                          batchCounter, percentOfMistakesBeforeSpecialize, specializeAllAwakeRulesOnFPMistake,
                                          receiveFeedbackBias, conservativeRuleGeneration, weightUpdateStrategy, withInertia, feedbackGap)
          } else {
            ExpertAdviceFunctions.process(train, train.annotation.toSet, inps,
                                          stateHandler, trueLabels, learningRate, epsilon, randomizedPrediction,
                                          batchCounter, percentOfMistakesBeforeSpecialize, specializeAllAwakeRulesOnFPMistake,
                                          receiveFeedbackBias, conservativeRuleGeneration, weightUpdateStrategy, withInertia,
                                          feedbackGap, inputTheory = Some(inputTheory))
          }

          // test
          val test = dataSlice.tail

          //println(s"training on ${train.time} testing on ${test.map(x => x.time).mkString(" ")}")
          //println(s"training on ${train.time}")

          stateHandler.perBatchError = Vector.empty[Int]
          /*
          test foreach { batch =>
            val trueLabels = batch.annotation.toSet
            val _receiveFeedbackBias = 0.0

            if (inputTheory.isEmpty) {
              ExpertAdviceFunctions.process(batch, batch.annotation.toSet, inps,
                stateHandler, trueLabels, learningRate, epsilon, randomizedPrediction,
                batchCounter, percentOfMistakesBeforeSpecialize, specializeAllAwakeRulesOnFPMistake, _receiveFeedbackBias,
                conservativeRuleGeneration, weightUpdateStrategy)
            } else {
              ExpertAdviceFunctions.process(batch, batch.annotation.toSet, inps,
                stateHandler, trueLabels, learningRate, epsilon, randomizedPrediction,
                batchCounter, percentOfMistakesBeforeSpecialize, specializeAllAwakeRulesOnFPMistake, _receiveFeedbackBias,
                conservativeRuleGeneration, weightUpdateStrategy, inputTheory = Some(inputTheory))
            }

          }
          */

          val currentError = stateHandler.perBatchError.sum
          acuumMistakes = acuumMistakes :+ currentError
          println(s"trained on ${train.time}, current error: $currentError")
        }
      }

      val file = new File(s"/home/nkatz/Desktop/${inps.targetHLE}-streaming")
      val bw = new BufferedWriter(new FileWriter(file))
      bw.write(acuumMistakes.mkString(","))
      bw.close()

      logger.info(s"Finished.")
      endTime = System.nanoTime()
      logger.info("Done.")
      //workers foreach(w => w ! PoisonPill)
      wrapUp()
      done = true
      context.system.terminate()

    }

  }

  def wrapUp() = {

    if (trainingDataOptions != testingDataOptions) {

      // show the info so far:
      wrapUp_NO_TEST()

      // and do the test
      logger.info("\n\nEvaluating on the test set\n\n")

      val _stateHandler = new StateHandler

      _stateHandler.ensemble = {
        val ensemble = stateHandler.ensemble

        val getRules = (allRules: List[Clause]) => {
          val nonEmptyBodied = allRules.filter(x => x.body.nonEmpty) // Refs of empty-bodied rules would be too immature.
          nonEmptyBodied.map(x => (x.refinements :+ x).minBy(-_.w_pos)) // use the ref with the best score so far
          //nonEmptyBodied.flatMap( x => (x.refinements :+ x)) // use all
        }

        val init = getRules(ensemble.initiationRules)
        val term = getRules(ensemble.terminationRules)

        ensemble.initiationRules = init
        ensemble.terminationRules = term
        ensemble
      }

      val testData = testingDataFunction(testingDataOptions)

      val _receiveFeedbackBias = 0.0 // Give no supervision for training, we're only testing
      testData foreach { batch =>
        _stateHandler.inertiaExpert.clear()
        val trueLabels = batch.annotation.toSet
        ExpertAdviceFunctions.process(batch, batch.annotation.toSet, inps,
                                      _stateHandler, trueLabels, learningRate, epsilon, randomizedPrediction,
                                      batchCounter, percentOfMistakesBeforeSpecialize, specializeAllAwakeRulesOnFPMistake, _receiveFeedbackBias,
                                      conservativeRuleGeneration, weightUpdateStrategy)
      }
      logger.info(s"Prequential error vector:\n${_stateHandler.perBatchError.map(x => x.toDouble)}")
      logger.info(s"Prequential error vector (Accumulated Error):\n${_stateHandler.perBatchError.scanLeft(0.0)(_ + _).tail}")
      logger.info(s"Total TPs: ${_stateHandler.totalTPs}, Total FPs: ${_stateHandler.totalFPs}, Total FNs: ${_stateHandler.totalFNs}, Total TNs: ${_stateHandler.totalTNs}")
      if (_receiveFeedbackBias != 1.0) {
        logger.info(s"\nReceived feedback on ${_stateHandler.receivedFeedback} rounds")
      }

      val tps = _stateHandler.totalTPs
      val fps = _stateHandler.totalFPs
      val fns = _stateHandler.totalFNs

      val microPrecision = tps.toDouble / (tps.toDouble + fps.toDouble)
      val microRecall = tps.toDouble / (tps.toDouble + fns.toDouble)
      val microFscore = (2 * microPrecision * microRecall) / (microPrecision + microRecall)

      println(s"Micro F1-score on test set: $microFscore")

    } else {
      wrapUp_NO_TEST()
    }

  }

  def wrapUp_NO_TEST() = {

      def show(in: List[Clause]) = {
        in.sortBy(x => -x.w_pos).
          map(x => x.showWithStats + "\n" + x.refinements.sortBy(x => -x.w_pos).map(x => x.showWithStats).mkString("\n      ")).mkString("\n")
      }

    //logger.info(show(stateHandler.ensemble.initiationRules))
    //logger.info(show(stateHandler.ensemble.terminationRules))

    logger.info(Theory(stateHandler.ensemble.initiationRules.sortBy(x => -x.w_pos)).showWithStats)
    logger.info(Theory(stateHandler.ensemble.terminationRules.sortBy(x => -x.w_pos)).showWithStats)

    logger.info(s"Prequential error vector:\n${stateHandler.perBatchError.map(x => x.toDouble)}")
    logger.info(s"Prequential error vector (Accumulated Error):\n${stateHandler.perBatchError.scanLeft(0.0)(_ + _).tail}")
    logger.info(s"Prequential (running) F1-score:\n${stateHandler.runningF1Score}")
    logger.info(s"Running rules number:\n${stateHandler.runningRulesNumber}")
    logger.info(s"Total TPs: ${stateHandler.totalTPs}, Total FPs: ${stateHandler.totalFPs}, Total FNs: ${stateHandler.totalFNs}, Total TNs: ${stateHandler.totalTNs}")
    logger.info(s"Total time: ${(endTime - startTime) / 1000000000.0}")

    if (randomizedPrediction) {
      logger.info(s"\nPredicted with initiation rules: ${stateHandler.predictedWithInitRule} times")
      logger.info(s"\nPredicted with terminated rules: ${stateHandler.predictedWithTermRule} times")
      logger.info(s"\nPredicted with inertia: ${stateHandler.predictedWithInertia} times")
    }

    //logger.info(s"Predictions vector:\n${stateHandler.predictionsVector}")

    logger.info(s"Total number of rounds: ${stateHandler.totalNumberOfRounds}")

    /*
    val tps = stateHandler.totalTPs
    val fps = stateHandler.totalFPs
    val fns = stateHandler.totalFNs

    val microPrecision = tps.toDouble/(tps.toDouble + fps.toDouble)
    val microRecall = tps.toDouble/(tps.toDouble + fns.toDouble)
    val microFscore = (2*microPrecision*microRecall)/(microPrecision+microRecall)

    println(s"Micro F1-score: $microFscore")
    */

    if (receiveFeedbackBias != 1.0 || feedbackGap != 0) {
      logger.info(s"\nReceived feedback on ${stateHandler.receivedFeedback} rounds")
    }
  }

}
