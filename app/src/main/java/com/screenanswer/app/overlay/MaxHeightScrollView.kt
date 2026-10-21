package com.screenanswer.app.overlay

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView
import kotlin.math.max

/** ScrollView that honours a maximum height, used to keep the floating answer panel compact. */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    var maxHeight: Int = 0
        set(value) {
            field = max(0, value)
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (maxHeight > 0) {
            val limited = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, limited)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
