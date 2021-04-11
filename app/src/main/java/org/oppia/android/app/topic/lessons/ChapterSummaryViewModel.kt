package org.oppia.android.app.topic.lessons

import androidx.lifecycle.ViewModel
import org.oppia.android.app.model.ChapterPlayState
import org.oppia.android.app.viewmodel.ObservableViewModel
import org.oppia.android.domain.exploration.ExplorationDataController
import org.oppia.android.util.data.DataProviders.Companion.toLiveData
import javax.inject.Inject

/** [ViewModel] for displaying a chapter summary. */
class ChapterSummaryViewModel(
  val chapterPlayState: ChapterPlayState,
  val explorationId: String,
  val chapterName: String,
  val storyId: String,
  val index: Int,
  private val chapterSummarySelector: ChapterSummarySelector
) : ObservableViewModel() {

  fun onClick(explorationId: String) {
    chapterSummarySelector.selectChapterSummary(storyId, explorationId)
  }
}
