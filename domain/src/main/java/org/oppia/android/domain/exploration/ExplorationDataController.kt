package org.oppia.android.domain.exploration

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Deferred
import org.oppia.android.app.model.CheckpointDatabase
import org.oppia.android.app.model.EphemeralState
import org.oppia.android.app.model.Exploration
import org.oppia.android.data.persistence.PersistentCacheStore
import org.oppia.android.domain.oppialogger.exceptions.ExceptionsController
import org.oppia.android.domain.profile.ProfileManagementController
import org.oppia.android.util.data.AsyncResult
import org.oppia.android.util.data.DataProvider
import org.oppia.android.util.data.DataProviders
import org.oppia.android.util.data.DataProviders.Companion.transform
import org.oppia.android.util.data.DataProviders.Companion.transformAsync
import javax.inject.Inject

private const val GET_EXPLORATION_BY_ID_PROVIDER_ID =
  "get_exploration_by_id_provider_id"

private const val ADD_CHECKPOINT_PROVIDER_ID =
  "add_checkpoint_provider_id"

private const val GET_CHECKPOINT_PROVIDER_ID =
  "get_checkpoint_provider_id"

/**
 * Controller for loading explorations by ID, or beginning to play an exploration.
 *
 * At most one exploration may be played at a given time, and its state will be managed by
 * [ExplorationProgressController].
 */
class ExplorationDataController @Inject constructor(
  private val explorationProgressController: ExplorationProgressController,
  private val explorationRetriever: ExplorationRetriever,
  private val dataProviders: DataProviders,
  private val exceptionsController: ExceptionsController,
  cacheStoreFactory: PersistentCacheStore.Factory
) {

  private val checkpointDataStore =  cacheStoreFactory.create("checkpoint_database", CheckpointDatabase.getDefaultInstance())

  /** Returns an [Exploration] given an ID. */
  fun getExplorationById(id: String): DataProvider<Exploration> {
    return dataProviders.createInMemoryDataProviderAsync(
      GET_EXPLORATION_BY_ID_PROVIDER_ID
    ) {
      retrieveExplorationById(id)
    }
  }

//  fun getStateByName(name: String): DataProvider<EphemeralState> {
//    return dataProviders.createInMemoryDataProviderAsync(
//      GET_CHECKPOINT_PROVIDER_ID
//    ) {
//      retrieveStateById(name)
//    }
//  }

  /**
   * Begins playing an exploration of the specified ID. This method is not expected to fail.
   * [ExplorationProgressController] should be used to manage the play state, and monitor the load success/failure of
   * the exploration.
   *
   * This must be called only if no active exploration is being played. The previous exploration must have first been
   * stopped using [stopPlayingExploration] otherwise an exception will be thrown.
   *
   * @return a one-time [LiveData] to observe whether initiating the play request succeeded. The exploration may still
   *     fail to load, but this provides early-failure detection.
   */
  fun startPlayingExploration(explorationId: String): LiveData<AsyncResult<Any?>> {
    return try {
      explorationProgressController.beginExplorationAsync(explorationId)
      MutableLiveData(AsyncResult.success<Any?>(null))
    } catch (e: Exception) {
      exceptionsController.logNonFatalException(e)
      MutableLiveData(AsyncResult.failed(e))
    }
  }

  /**
   * Finishes the most recent exploration started by [startPlayingExploration]. This method should only be called if an
   * active exploration is being played, otherwise an exception will be thrown.
   */
  fun stopPlayingExploration(): LiveData<AsyncResult<Any?>> {
    return try {
      explorationProgressController.finishExplorationAsync()
      MutableLiveData(AsyncResult.success<Any?>(null))
    } catch (e: Exception) {
      exceptionsController.logNonFatalException(e)
      MutableLiveData(AsyncResult.failed(e))
    }
  }

  fun getCheckpoint(explorationId: String): DataProvider<String?> {
    return checkpointDataStore.transformAsync(GET_CHECKPOINT_PROVIDER_ID) {
      AsyncResult.success(it.checkpointsMap[explorationId])
    }
  }

  fun checkpointCurrentState(explorationId: String): DataProvider<Any?> {
    val deffered = checkpointDataStore.storeDataWithCustomChannelAsync(
      updateInMemoryCache = true
    ) {
      Log.d("Exploration Activity", explorationProgressController.getCurrentState().toString())
        val checkpointDatabaseBuilder =
          it.toBuilder()
            .putCheckpoints(
              explorationId,
              explorationProgressController.getCurrentStateName()
            )
        Pair(checkpointDatabaseBuilder.build(), CheckpointActionStatus.SUCCESS)
    }
    return dataProviders.createInMemoryDataProviderAsync(ADD_CHECKPOINT_PROVIDER_ID) {
      return@createInMemoryDataProviderAsync getDefferedResult(deffered)
    }
  }

  private suspend fun getDefferedResult(deffered: Deferred<CheckpointActionStatus>): AsyncResult<Any?> {
    return when (deffered.await()) {
      CheckpointActionStatus.SUCCESS -> AsyncResult.success(null)
      CheckpointActionStatus.FAILURE -> AsyncResult.failed(Exception("Siemthing"))
      CheckpointActionStatus.PENDING -> AsyncResult.success(null)
    }
  }

  private enum class CheckpointActionStatus {
    SUCCESS,
    FAILURE,
    PENDING
  }

  // DataProviders expects this function to be a suspend function.
  @Suppress("RedundantSuspendModifier")
  private suspend fun retrieveExplorationById(explorationId: String): AsyncResult<Exploration> {
    return try {
      AsyncResult.success(explorationRetriever.loadExploration(explorationId))
    } catch (e: Exception) {
      exceptionsController.logNonFatalException(e)
      AsyncResult.failed(e)
    }
  }

//  @Suppress("RedundantSuspendModifier")
//  private suspend fun retrieveStateById(name: String): AsyncResult<EphemeralState> {
//    return try {
//      explorationProgressController.getStateByName(name)
//    } catch (e: Exception) {
//      exceptionsController.logNonFatalException(e)
//      AsyncResult.failed(e)
//    }
//  }
}
