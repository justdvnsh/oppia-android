package org.oppia.android.app.utility

import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.forEachIndexed
import androidx.core.view.isVisible
import org.oppia.android.R
import org.oppia.android.app.model.ImageWithRegions
import org.oppia.android.app.player.state.ImageRegionSelectionInteractionView
import org.oppia.android.app.shim.ViewBindingShim
import org.oppia.android.domain.oppialogger.OppiaLogger
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Helper class to handle clicks on an image along with highlighting the selected region .
 */
class ClickableAreasImage(
  private val imageView: ImageRegionSelectionInteractionView,
  private val parentView: FrameLayout,
  private val listener: OnClickableAreaClickedListener,
  private val bindingInterface: ViewBindingShim
) {
  init {
    imageView.setOnTouchListener { view, motionEvent ->
      if (motionEvent.action == MotionEvent.ACTION_DOWN) {
        onPhotoTap(motionEvent.x, motionEvent.y)
      }
      return@setOnTouchListener false
    }
  }

  @Inject
  lateinit var oppiaLogger: OppiaLogger

  /**
   * Called when an image is clicked.
   *
   * @param view the original view on which the tap/click occurs.
   * @param x the relative x coordinate according to image
   * @param y the relative y coordinate according to image
   */
  private fun onPhotoTap(x: Float, y: Float) {
    // Show default region for non-accessibility cases and this will be only called when user taps on unspecified region.
    if (!imageView.isAccessibilityEnabled()) {
      resetRegionSelectionViews()
      val defaultRegion = bindingInterface.getDefaultRegion(parentView)
      defaultRegion.setBackgroundResource(R.drawable.selected_region_background)
      defaultRegion.x = x
      defaultRegion.y = y
      listener.onClickableAreaTouched(DefaultRegionClickedEvent())
    }
  }

  /** Function to remove the background from the views.*/
  private fun resetRegionSelectionViews() {
    parentView.forEachIndexed { index: Int, childView: View ->
      // Remove any previously selected region excluding 0th index(image view)
      if (index > 0) {
        childView.setBackgroundResource(0)
      }
    }
  }

  /** Get X co-ordinate scaled according to image.*/
  private fun getXCoordinate(x: Float): Float {
    return x * getImageViewContentWidth()
  }

  /** Get Y co-ordinate scaled according to image.*/
  private fun getYCoordinate(y: Float): Float {
    return y * getImageViewContentHeight()
  }

  private fun getImageViewContentWidth(): Int {
    return imageView.width - imageView.paddingStart - imageView.paddingEnd
  }

  private fun getImageViewContentHeight(): Int {
    return imageView.height - imageView.paddingTop - imageView.paddingBottom
  }

  /** Add selectable regions to [FrameLayout].*/
  fun addRegionViews() {
    parentView.let {
      if (it.childCount > 2) {
        try {
          it.removeViews(2, it.childCount - 1) // remove all other views
        } catch (e: IndexOutOfBoundsException) {
          if (::oppiaLogger.isInitialized)
            oppiaLogger.e(
              "ClickableAreaImage",
              "Throws exception on Index out of bound",
              e
            )
        }
      }
      imageView.getClickableAreas().forEach { clickableArea ->
        val imageRect = RectF(
          getXCoordinate(clickableArea.region.area.upperLeft.x),
          getYCoordinate(clickableArea.region.area.upperLeft.y),
          getXCoordinate(clickableArea.region.area.lowerRight.x),
          getYCoordinate(clickableArea.region.area.lowerRight.y)
        )
        val layoutParams = FrameLayout.LayoutParams(
          imageRect.width().roundToInt(),
          imageRect.height().roundToInt()
        )
        val newView = View(it.context)
        // ClickableArea coordinates are not laid-out properly in RTL. The image region coordinates are
        // from left-to-right with an upper left origin and touch coordinates from Android start from the
        // right in RTL mode. Thus, to avoid this situation, force layout direction to LTR in all situations.
        ViewCompat.setLayoutDirection(it, ViewCompat.LAYOUT_DIRECTION_LTR)
        newView.layoutParams = layoutParams
        newView.x = imageRect.left
        newView.y = imageRect.top
        newView.isClickable = true
        newView.isFocusable = true
        newView.isFocusableInTouchMode = true
        newView.tag = clickableArea.label
        newView.setOnTouchListener { _, event ->
          if (event.action == MotionEvent.ACTION_DOWN) {
            showOrHideRegion(newView, clickableArea)
          }
          return@setOnTouchListener true
        }
        if (imageView.isAccessibilityEnabled()) {
          // Make default region visibility gone when talkback enabled to avoid any accidental touch.
          val defaultRegion = bindingInterface.getDefaultRegion(parentView)
          defaultRegion.isVisible = false
          newView.setOnClickListener {
            showOrHideRegion(newView, clickableArea)
          }
        }
        it.addView(newView)
        newView.requestLayout()
      }
    }
  }

  private fun showOrHideRegion(newView: View, clickableArea: ImageWithRegions.LabeledRegion) {
    resetRegionSelectionViews()
    listener.onClickableAreaTouched(NamedRegionClickedEvent(clickableArea.label))
    newView.setBackgroundResource(R.drawable.selected_region_background)
  }
}
