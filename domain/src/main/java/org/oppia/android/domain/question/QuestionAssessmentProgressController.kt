package org.oppia.android.domain.question

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.oppia.android.app.model.AnsweredQuestionOutcome
import org.oppia.android.app.model.EphemeralQuestion
import org.oppia.android.app.model.EphemeralState
import org.oppia.android.app.model.ProfileId
import org.oppia.android.app.model.Question
import org.oppia.android.app.model.State
import org.oppia.android.app.model.UserAnswer
import org.oppia.android.app.model.UserAssessmentPerformance
import org.oppia.android.domain.classify.AnswerClassificationController
import org.oppia.android.domain.classify.ClassificationResult.OutcomeWithMisconception
import org.oppia.android.domain.hintsandsolution.HintHandler
import org.oppia.android.domain.oppialogger.exceptions.ExceptionsController
import org.oppia.android.domain.question.QuestionAssessmentProgress.TrainStage
import org.oppia.android.domain.translation.TranslationController
import org.oppia.android.util.data.AsyncDataSubscriptionManager
import org.oppia.android.util.data.AsyncResult
import org.oppia.android.util.data.DataProvider
import org.oppia.android.util.data.DataProviders
import org.oppia.android.util.data.DataProviders.Companion.combineWith
import org.oppia.android.util.data.DataProviders.Companion.transformNested
import org.oppia.android.util.data.DataProviders.NestedTransformedDataProvider
import org.oppia.android.util.locale.OppiaLocale
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

private const val CREATE_CURRENT_QUESTION_DATA_PROVIDER_ID =
  "create_current_question_data_provider_id"
private const val CREATE_CURRENT_QUESTION_DATA_WITH_TRANSLATION_CONTEXT_PROVIDER_ID =
  "create_current_question_data_with_translation_context_provider_id"
private const val BEGIN_QUESTION_TRAINING_SESSION_PROVIDER_ID =
  "begin_question_training_session_provider_id"
private const val CREATE_EMPTY_QUESTIONS_LIST_DATA_PROVIDER_ID =
  "create_empty_questions_list_data_provider_id"
private const val SUBMIT_ANSWER_PROVIDER_ID = "submit_answer_provider_id"

/**
 * Controller that tracks and reports the learner's ephemeral/non-persisted progress through a
 * practice training session. Note that this controller only supports one active training session at
 * a time.
 *
 * The current training session is started via the question training controller.
 *
 * This class is thread-safe, but the order of applied operations is arbitrary. Calling code should
 * take care to ensure that uses of this class do not specifically depend on ordering.
 */
@Singleton
class QuestionAssessmentProgressController @Inject constructor(
  private val dataProviders: DataProviders,
  private val asyncDataSubscriptionManager: AsyncDataSubscriptionManager,
  private val answerClassificationController: AnswerClassificationController,
  private val exceptionsController: ExceptionsController,
  private val hintHandlerFactory: HintHandler.Factory,
  private val translationController: TranslationController
) : HintHandler.HintMonitor {
  // TODO(#247): Add support for populating the list of skill IDs to review at the end of the
  //  training session.
  // TODO(#248): Add support for the assessment ending prematurely due to learner demonstrating
  //  sufficient proficiency.

  private val progress = QuestionAssessmentProgress()
  private lateinit var hintHandler: HintHandler
  private val progressLock = ReentrantLock()

  @Inject
  internal lateinit var scoreCalculatorFactory: QuestionAssessmentCalculation.Factory
  private val currentQuestionDataProvider: NestedTransformedDataProvider<EphemeralQuestion> =
    createCurrentQuestionDataProvider(createEmptyQuestionsListDataProvider())

  internal fun beginQuestionTrainingSession(
    questionsListDataProvider: DataProvider<List<Question>>,
    profileId: ProfileId
  ) {
    progressLock.withLock {
      check(progress.trainStage == TrainStage.NOT_IN_TRAINING_SESSION) {
        "Cannot start a new training session until the previous one is completed."
      }
      progress.currentProfileId = profileId

      hintHandler = hintHandlerFactory.create(this)
      progress.advancePlayStageTo(TrainStage.LOADING_TRAINING_SESSION)
      currentQuestionDataProvider.setBaseDataProvider(
        questionsListDataProvider,
        this::retrieveCurrentQuestionAsync
      )
      asyncDataSubscriptionManager.notifyChangeAsync(BEGIN_QUESTION_TRAINING_SESSION_PROVIDER_ID)
    }
  }

  internal fun finishQuestionTrainingSession() {
    progressLock.withLock {
      check(progress.trainStage != TrainStage.NOT_IN_TRAINING_SESSION) {
        "Cannot stop a new training session which wasn't started."
      }
      progress.advancePlayStageTo(TrainStage.NOT_IN_TRAINING_SESSION)
      currentQuestionDataProvider.setBaseDataProvider(
        createEmptyQuestionsListDataProvider(), this::retrieveCurrentQuestionAsync
      )
    }
  }

  override fun onHelpIndexChanged() {
    asyncDataSubscriptionManager.notifyChangeAsync(CREATE_CURRENT_QUESTION_DATA_PROVIDER_ID)
  }

  /**
   * Submits an answer to the current question and returns how the UI should respond to this answer.
   * The returned [LiveData] will only have at most two results posted: a pending result, and then a
   * completed success/failure result. Failures in this case represent a failure of the app
   * (possibly due to networking conditions). The app should report this error in a consumable way
   * to the user so that they may take action on it. No additional values will be reported to the
   * [LiveData]. Each call to this method returns a new, distinct, [LiveData] object that must be
   * observed. Note also that the returned [LiveData] is not guaranteed to begin with a pending
   * state.
   *
   * If the app undergoes a configuration change, calling code should rely on the [LiveData] from
   * [getCurrentQuestion] to know whether a current answer is pending. That [LiveData] will have its
   * state changed to pending during answer submission and until answer resolution.
   *
   * Submitting an answer should result in the learner staying in the current question or moving to
   * a new question in the training session. Note that once a correct answer is processed, the
   * current state reported to [getCurrentQuestion] will change from a pending question to a
   * completed question since the learner completed that question card. The learner can then proceed
   * from the current completed question to the next pending question using [moveToNextQuestion].
   *
   * This method cannot be called until a training session has started and [getCurrentQuestion]
   * returns a non-pending result or the result will fail. Calling code must also take care not to
   * allow users to submit an answer while a previous answer is pending. That scenario will also
   * result in a failed answer submission.
   *
   * No assumptions should be made about the completion order of the returned [LiveData] vs. the
   * [LiveData] from [getCurrentQuestion]. Also note that the returned [LiveData] will only have a
   * single value and not be reused after that point.
   */
  fun submitAnswer(answer: UserAnswer): LiveData<AsyncResult<AnsweredQuestionOutcome>> {
    try {
      progressLock.withLock {
        check(progress.trainStage != TrainStage.NOT_IN_TRAINING_SESSION) {
          "Cannot submit an answer if a training session has not yet begun."
        }
        check(progress.trainStage != TrainStage.LOADING_TRAINING_SESSION) {
          "Cannot submit an answer while the training session is being loaded."
        }
        check(progress.trainStage != TrainStage.SUBMITTING_ANSWER) {
          "Cannot submit an answer while another answer is pending."
        }

        // Notify observers that the submitted answer is currently pending.
        progress.advancePlayStageTo(TrainStage.SUBMITTING_ANSWER)
        asyncDataSubscriptionManager.notifyChangeAsync(SUBMIT_ANSWER_PROVIDER_ID)

        lateinit var answeredQuestionOutcome: AnsweredQuestionOutcome
        try {
          val topPendingState = progress.stateDeck.getPendingTopState()
          val classificationResult =
            answerClassificationController.classify(
              topPendingState.interaction, answer.answer, answer.writtenTranslationContext
            )
          answeredQuestionOutcome =
            progress.stateList.computeAnswerOutcomeForResult(classificationResult.outcome)
          progress.stateDeck.submitAnswer(answer, answeredQuestionOutcome.feedback)

          // Track the number of answers the user submitted, including any misconceptions
          val misconception = if (classificationResult is OutcomeWithMisconception) {
            classificationResult.taggedSkillId
          } else null
          progress.trackAnswerSubmitted(misconception)

          // Do not proceed unless the user submitted the correct answer.
          if (answeredQuestionOutcome.isCorrectAnswer) {
            progress.completeCurrentQuestion()
            val newState = if (!progress.isAssessmentCompleted()) {
              // Only push the next state if the assessment isn't completed.
              progress.getNextState()
            } else {
              // Otherwise, push a synthetic state for the end of the session.
              State.getDefaultInstance()
            }
            progress.stateDeck.pushState(newState, prohibitSameStateName = false)
            hintHandler.finishState(newState)
          } else {
            // Schedule a new hints or solution or show a new hint or solution immediately based on
            // the current ephemeral state of the training session because a new wrong answer was
            // submitted.
            hintHandler.handleWrongAnswerSubmission(
              computeBaseCurrentEphemeralState().pendingState.wrongAnswerCount
            )
          }
        } finally {
          // Ensure that the user always returns to the VIEWING_STATE stage to avoid getting stuck
          // in an 'always submitting answer' situation. This can specifically happen if answer
          // classification throws an exception.
          progress.advancePlayStageTo(TrainStage.VIEWING_STATE)
        }

        asyncDataSubscriptionManager.notifyChangeAsync(CREATE_CURRENT_QUESTION_DATA_PROVIDER_ID)

        return MutableLiveData(AsyncResult.success(answeredQuestionOutcome))
      }
    } catch (e: Exception) {
      exceptionsController.logNonFatalException(e)
      return MutableLiveData(AsyncResult.failed(e))
    }
  }

  /**
   * Notifies the controller that the user wishes to reveal a hint.
   *
   * @param hintIndex index of the hint that was revealed in the hint list of the current pending
   *     state
   * @return a one-time [LiveData] that indicates success/failure of the operation (the actual
   *     payload of the result isn't relevant)
   */
  fun submitHintIsRevealed(hintIndex: Int): LiveData<AsyncResult<Any?>> {
    try {
      progressLock.withLock {
        check(progress.trainStage != TrainStage.NOT_IN_TRAINING_SESSION) {
          "Cannot submit an answer if a training session has not yet begun."
        }
        check(progress.trainStage != TrainStage.LOADING_TRAINING_SESSION) {
          "Cannot submit an answer while the training session is being loaded."
        }
        check(progress.trainStage != TrainStage.SUBMITTING_ANSWER) {
          "Cannot submit an answer while another answer is pending."
        }
        try {
          progress.trackHintViewed()
          hintHandler.viewHint(hintIndex)
        } finally {
          // Ensure that the user always returns to the VIEWING_STATE stage to avoid getting stuck
          // in an 'always showing hint' situation. This can specifically happen if hint throws an
          // exception.
          progress.advancePlayStageTo(TrainStage.VIEWING_STATE)
        }
        asyncDataSubscriptionManager.notifyChangeAsync(CREATE_CURRENT_QUESTION_DATA_PROVIDER_ID)
        return MutableLiveData(AsyncResult.success(null))
      }
    } catch (e: Exception) {
      exceptionsController.logNonFatalException(e)
      return MutableLiveData(AsyncResult.failed(e))
    }
  }

  /**
   * Notifies the controller that the user has revealed the solution to the current state.
   *
   * @return a one-time [LiveData] that indicates success/failure of the operation (the actual
   *     payload of the result isn't relevant)
   */
  fun submitSolutionIsRevealed(): LiveData<AsyncResult<Any?>> {
    try {
      progressLock.withLock {
        check(progress.trainStage != TrainStage.NOT_IN_TRAINING_SESSION) {
          "Cannot submit an answer if a training session has not yet begun."
        }
        check(progress.trainStage != TrainStage.LOADING_TRAINING_SESSION) {
          "Cannot submit an answer while the training session is being loaded."
        }
        check(progress.trainStage != TrainStage.SUBMITTING_ANSWER) {
          "Cannot submit an answer while another answer is pending."
        }
        try {
          progress.trackSolutionViewed()
          hintHandler.viewSolution()
        } finally {
          // Ensure that the user always returns to the VIEWING_STATE stage to avoid getting stuck
          // in an 'always showing solution' situation. This can specifically happen if solution
          // throws an exception.
          progress.advancePlayStageTo(TrainStage.VIEWING_STATE)
        }

        asyncDataSubscriptionManager.notifyChangeAsync(CREATE_CURRENT_QUESTION_DATA_PROVIDER_ID)
        return MutableLiveData(AsyncResult.success(null))
      }
    } catch (e: Exception) {
      exceptionsController.logNonFatalException(e)
      return MutableLiveData(AsyncResult.failed(e))
    }
  }

  /**
   * Navigates to the next question in the assessment. This method is only valid if the current
   * [EphemeralQuestion] reported by [getCurrentQuestion] is a completed question. Calling code is
   * responsible for ensuring this method is only called when it's possible to navigate forward.
   *
   * Note that if the current question is pending, the user needs to submit a correct answer via
   * [submitAnswer] before forward navigation can occur.
   *
   * @return a one-time [LiveData] indicating whether the movement to the next question was
   *     successful, or a failure if question navigation was attempted at an invalid time (such as
   *     if the current question is pending or terminal). It's recommended that calling code only
   *     listen to this result for failures, and instead rely on [getCurrentQuestion] for observing
   *     a successful transition to another question.
   */
  fun moveToNextQuestion(): LiveData<AsyncResult<Any?>> {
    try {
      progressLock.withLock {
        check(progress.trainStage != TrainStage.NOT_IN_TRAINING_SESSION) {
          "Cannot navigate to a next question if a training session has not begun."
        }
        check(progress.trainStage != TrainStage.SUBMITTING_ANSWER) {
          "Cannot navigate to a next question if an answer submission is pending."
        }
        progress.stateDeck.navigateToNextState()
        // Track whether the learner has moved to a new card.
        if (progress.isViewingMostRecentQuestion()) {
          // Update the hint state and maybe schedule new help when user moves to the pending top
          // state.
          hintHandler.navigateBackToLatestPendingState()
          progress.processNavigationToNewQuestion()
        }
        asyncDataSubscriptionManager.notifyChangeAsync(CREATE_CURRENT_QUESTION_DATA_PROVIDER_ID)
      }
      return MutableLiveData(AsyncResult.success<Any?>(null))
    } catch (e: Exception) {
      exceptionsController.logNonFatalException(e)
      return MutableLiveData(AsyncResult.failed(e))
    }
  }

  /**
   * Returns a [DataProvider] monitoring the current [EphemeralQuestion] the learner is currently
   * viewing. If this state corresponds to a a terminal state, then the learner has completed the
   * training session. Note that [moveToNextQuestion] will automatically update observers of this
   * data provider when the next question is navigated to.
   *
   * This [DataProvider] may switch from a completed to a pending result during transient operations
   * like submitting an answer via [submitAnswer]. Calling code should be made resilient to this by
   * caching the current question object to display since it may disappear temporarily during answer
   * submission. Calling code should persist this state object across configuration changes if
   * needed since it cannot rely on this [DataProvider] for immediate UI reconstitution after
   * configuration changes.
   *
   * The underlying question returned by this function can only be changed by calls to
   * [moveToNextQuestion], or the question training controller if another question session begins.
   * UI code can be confident only calls from the UI layer will trigger changes here to ensure
   * atomicity between receiving and making question state changes.
   *
   * This method is safe to be called before a training session has started. If there is no ongoing
   * session, it should return a pending state, which means the returned value can switch from a
   * success or failure state back to pending.
   */
  fun getCurrentQuestion(): DataProvider<EphemeralQuestion> = progressLock.withLock {
    val providerId = CREATE_CURRENT_QUESTION_DATA_WITH_TRANSLATION_CONTEXT_PROVIDER_ID
    return translationController.getWrittenTranslationContentLocale(
      progress.currentProfileId
    ).combineWith(currentQuestionDataProvider, providerId) { contentLocale, currentQuestion ->
      return@combineWith augmentEphemeralQuestion(contentLocale, currentQuestion)
    }
  }

  /**
   * Returns a [DataProvider] monitoring the [UserAssessmentPerformance] corresponding to the user's
   * computed overall performance this practice session.
   *
   * This method should only be called at the end of a practice session, after all the questions
   * have been completed.
   */
  fun calculateScores(skillIdList: List<String>): DataProvider<UserAssessmentPerformance> =
    progressLock.withLock {
      return dataProviders.createInMemoryDataProviderAsync(
        "user_assessment_performance"
      ) {
        retrieveUserAssessmentPerformanceAsync(skillIdList)
      }
    }

  private fun computeBaseCurrentEphemeralState(): EphemeralState =
    progress.stateDeck.getCurrentEphemeralState(hintHandler.getCurrentHelpIndex())

  @Suppress("RedundantSuspendModifier")
  private suspend fun retrieveUserAssessmentPerformanceAsync(
    skillIdList: List<String>
  ): AsyncResult<UserAssessmentPerformance> {
    progressLock.withLock {
      val scoreCalculator =
        scoreCalculatorFactory.create(skillIdList, progress.questionSessionMetrics)
      return AsyncResult.success(scoreCalculator.computeAll())
    }
  }

  private fun createCurrentQuestionDataProvider(
    questionsListDataProvider: DataProvider<List<Question>>
  ): NestedTransformedDataProvider<EphemeralQuestion> {
    return questionsListDataProvider.transformNested(
      CREATE_CURRENT_QUESTION_DATA_PROVIDER_ID,
      this::retrieveCurrentQuestionAsync
    )
  }

  @Suppress("RedundantSuspendModifier") // 'suspend' expected by DataProviders.
  private suspend fun retrieveCurrentQuestionAsync(
    questionsList: List<Question>
  ): AsyncResult<EphemeralQuestion> {
    progressLock.withLock {
      return try {
        when (progress.trainStage) {
          TrainStage.NOT_IN_TRAINING_SESSION -> AsyncResult.pending()
          TrainStage.LOADING_TRAINING_SESSION -> {
            // If the assessment hasn't yet been initialized, initialize it
            // now that a list of questions is available.
            initializeAssessment(questionsList)
            progress.advancePlayStageTo(TrainStage.VIEWING_STATE)
            AsyncResult.success(
              retrieveEphemeralQuestionState(questionsList)
            )
          }
          TrainStage.VIEWING_STATE -> AsyncResult.success(
            retrieveEphemeralQuestionState(questionsList)
          )
          TrainStage.SUBMITTING_ANSWER -> AsyncResult.pending()
        }
      } catch (e: Exception) {
        exceptionsController.logNonFatalException(e)
        AsyncResult.failed(e)
      }
    }
  }

  /**
   * Augments the specified [EphemeralQuestion] [AsyncResult] by attaching the necessary context to
   * translate the question.
   */
  private fun augmentEphemeralQuestion(
    writtenTranslationContentLocale: OppiaLocale.ContentLocale,
    ephemeralQuestion: EphemeralQuestion
  ): EphemeralQuestion {
    return ephemeralQuestion.toBuilder().apply {
      ephemeralState = ephemeralState.toBuilder().apply {
        writtenTranslationContext =
          translationController.computeWrittenTranslationContext(
            state.writtenTranslationsMap, writtenTranslationContentLocale
          )
      }.build()
    }.build()
  }

  private fun retrieveEphemeralQuestionState(
    questionsList: List<Question>
  ): EphemeralQuestion {
    val currentQuestionIndex = progress.getCurrentQuestionIndex()
    val ephemeralQuestionBuilder = EphemeralQuestion.newBuilder()
      .setEphemeralState(computeBaseCurrentEphemeralState())
      .setCurrentQuestionIndex(currentQuestionIndex)
      .setTotalQuestionCount(progress.getTotalQuestionCount())
      .setInitialTotalQuestionCount(progress.getTotalQuestionCount())
    if (currentQuestionIndex < questionsList.size) {
      ephemeralQuestionBuilder.question = questionsList[currentQuestionIndex]
    }
    return ephemeralQuestionBuilder.build()
  }

  private fun initializeAssessment(questionsList: List<Question>) {
    check(questionsList.isNotEmpty()) { "Cannot start a training session with zero questions." }
    progress.initialize(questionsList)
    // Update hint state to schedule task to show new help.
    hintHandler.startWatchingForHintsInNewState(progress.stateDeck.getCurrentState())
  }

  /** Returns a temporary [DataProvider] that always provides an empty list of [Question]s. */
  private fun createEmptyQuestionsListDataProvider(): DataProvider<List<Question>> {
    return dataProviders.createInMemoryDataProvider(CREATE_EMPTY_QUESTIONS_LIST_DATA_PROVIDER_ID) {
      listOf<Question>()
    }
  }
}
