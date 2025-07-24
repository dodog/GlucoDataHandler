package de.michelinside.glucodatahandler.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import de.michelinside.glucodatahandler.R
import de.michelinside.glucodatahandler.common.Constants
import de.michelinside.glucodatahandler.common.ReceiveData
import de.michelinside.glucodatahandler.common.notifier.InternalNotifier
import de.michelinside.glucodatahandler.common.notifier.NotifierInterface
import de.michelinside.glucodatahandler.common.notifier.NotifySource
import de.michelinside.glucodatahandler.common.utils.BitmapUtils
import de.michelinside.glucodatahandler.common.utils.Utils
import android.text.Spanned
import de.michelinside.glucodatahandler.common.chart.ChartBitmapHandler

abstract class WallpaperBase(protected val context: Context, protected val LOG_ID: String): NotifierInterface, SharedPreferences.OnSharedPreferenceChangeListener {
    protected abstract val enabledPref: String
    protected abstract val stylePref: String
    protected abstract val sizePref: String
    protected open val MIN_SIZE = 6f
    protected open val MIN_VALUE_SIZE = 6f
    protected open val VALUE_RESIZE_FACTOR = 4f
    protected open val MAX_SIZE = 24f
    protected open val DEFAULT_FONT_SIZE = 12f
    protected var enabled = false
    protected var paused = false
    protected var style = Constants.WIDGET_STYLE_GLUCOSE_TREND
    protected var size = 10
    protected lateinit var sharedPref: SharedPreferences
    private var curWallpaper: Bitmap? = null
    private var oldWallpaper: Bitmap? = null

    protected val active: Boolean get() {
        return enabled && !paused
    }

    fun create() {
        try {
            Log.d(LOG_ID, "create called")
            sharedPref = context.getSharedPreferences(Constants.SHARED_PREF_TAG, Context.MODE_PRIVATE)
            sharedPref.registerOnSharedPreferenceChangeListener(this)
            initSettings(sharedPref)
        } catch (exc: Exception) {
            Log.e(LOG_ID, "create exception: " + exc.message.toString() )
        }
    }

    fun destroy() {
        try {
            Log.d(LOG_ID, "destroy called")
            disable()
            removeBitmap()
            InternalNotifier.remNotifier(context, this)
            sharedPref.unregisterOnSharedPreferenceChangeListener(this)
            if(curWallpaper != null) {
                Log.i(LOG_ID, "Destroy current wallpaper")
                curWallpaper?.recycle()
                curWallpaper = null
            }
            if(oldWallpaper != null) {
                Log.i(LOG_ID, "Destroy old wallpaper")
                oldWallpaper?.recycle()
                oldWallpaper = null
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "destroy exception: " + exc.message.toString() )
        }
    }

    fun pause() {
        paused = true
        updateNotifier()
        disable()
        pauseBitmap()
    }

    fun resume() {
        paused = false
        updateNotifier()
        resumeBitmap()
        enable()
    }

    protected abstract fun enable()
    protected abstract fun disable()
    protected abstract fun update()

    protected open fun initSettings(sharedPreferences: SharedPreferences) {
        style = sharedPreferences.getString(stylePref, style)?: style
        size = sharedPreferences.getInt(sizePref, size)
        enabled = sharedPreferences.getBoolean(enabledPref, false)
        Log.d(LOG_ID, "initSettings called for style $style and size $size and enabled $enabled")
        if(enabled) {
            updateChartCreation()
            updateNotifier()
            enable()
        }
    }


    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        try {
            Log.d(LOG_ID, "onSharedPreferenceChanged called for key " + key)
            when(key) {
                stylePref -> {
                    if (key == stylePref && style != sharedPreferences.getString(stylePref, style)) {
                        style = sharedPreferences.getString(stylePref, style)!!
                        Log.d(LOG_ID, "New style: $style")
                        updateChartCreation()
                        updateNotifier()
                        if(active)
                            update()
                    }
                }
                sizePref -> {
                    if (size != sharedPreferences.getInt(sizePref, size)) {
                        size = sharedPreferences.getInt(sizePref, size)
                        Log.d(LOG_ID, "New size: $size")
                        if(active)
                            update()
                    }
                }
                enabledPref -> {
                    if (enabled != sharedPreferences.getBoolean(enabledPref, false)) {
                        enabled = sharedPreferences.getBoolean(enabledPref, false)
                        Log.d(LOG_ID, "Enabled changed: $enabled")
                        updateChartCreation()
                        updateNotifier()
                        if (enabled)
                            enable()
                        else
                            disable()
                    }
                }
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "onSharedPreferenceChanged exception: " + exc.toString() )
        }
    }

    override fun OnNotifyData(context: Context, dataSource: NotifySource, extras: Bundle?) {
        try {
            Log.d(LOG_ID, "OnNotifyData called for source $dataSource with extras ${Utils.dumpBundle(extras)} - active: $active - graph-id: ${if(hasBitmap()) ChartBitmapHandler.chartId else -1} - elapsed: ${ReceiveData.getElapsedTimeMinute()}")
            if(!active)
                return
            if (dataSource == NotifySource.GRAPH_CHANGED && ChartBitmapHandler.active && extras?.getInt(Constants.GRAPH_ID) != ChartBitmapHandler.chartId) {
                Log.v(LOG_ID, "Ignore graph changed as it is not for this chart")
                return  // ignore as it is not for this graph
            }
            if(dataSource == NotifySource.TIME_VALUE && hasBitmap() && ReceiveData.getElapsedTimeMinute().mod(2) == 0) {
                Log.d(LOG_ID, "Ignore time value and wait for chart update")
                return
            }
            Log.i(LOG_ID, "Update called for source $dataSource - style $style - size $size")
            update()
        } catch (exc: Exception) {
            Log.e(LOG_ID, "OnNotifyData exception: " + exc.toString() )
        }
    }

    fun receycleOldWallpaper() {
        if(oldWallpaper != null) {
            Log.i(LOG_ID, "Receycle old wallpaper")
            if(oldWallpaper?.isRecycled == false)
                oldWallpaper?.recycle()
            oldWallpaper = null
        }
    }

    protected open fun getFilters() : MutableSet<NotifySource> {
        return mutableSetOf()
    }

    private fun updateNotifier() {
        Log.d(LOG_ID, "updateNotifier called - active=$active")
        if (active) {
            val filter = mutableSetOf(
                NotifySource.SETTINGS
            )
            filter.addAll(getFilters())
            if(style == Constants.WIDGET_STYLE_CHART_GLUCOSE_TREND_TIME_DELTA_IOB_COB) {
                filter.add(NotifySource.GRAPH_CHANGED)
            } else {
                filter.add(NotifySource.BROADCAST)
                filter.add(NotifySource.MESSAGECLIENT)
            }
            when (style) {
                Constants.WIDGET_STYLE_GLUCOSE_TREND_TIME_DELTA -> {
                    filter.add(NotifySource.TIME_VALUE)
                }
                Constants.WIDGET_STYLE_CHART_GLUCOSE_TREND_TIME_DELTA_IOB_COB,
                Constants.WIDGET_STYLE_GLUCOSE_TREND_TIME_DELTA_IOB_COB -> {
                    filter.add(NotifySource.TIME_VALUE)
                    filter.add(NotifySource.IOB_COB_CHANGE)
                }
                else -> {
                    filter.add(NotifySource.OBSOLETE_VALUE)
                }
            }
            Log.d(LOG_ID, "Notifier filter for active: $filter")
            InternalNotifier.addNotifier(context, this, filter)
        } else {
            val filter = getFilters()
            Log.d(LOG_ID, "Notifier filter for inactive: $filter")
            if(filter.isNotEmpty())
                InternalNotifier.addNotifier(context, this, filter)
            else
                InternalNotifier.remNotifier(context, this)
        }
    }

    private fun getChart(): Bitmap? {
        return ChartBitmapHandler.getBitmap()
    }

    private fun updateChartCreation() {
        if(enabled && style == Constants.WIDGET_STYLE_CHART_GLUCOSE_TREND_TIME_DELTA_IOB_COB)
            createBitmap()
        else
            removeBitmap()
    }

    private fun createBitmap() {
        Log.i(LOG_ID, "Create bitmap")
        ChartBitmapHandler.register(context, this.javaClass.simpleName)
    }

    protected fun removeBitmap() {
        ChartBitmapHandler.unregister(this.javaClass.simpleName)
    }

    protected fun pauseBitmap() {
        ChartBitmapHandler.pause(this.javaClass.simpleName)
    }

    protected fun resumeBitmap() {
        ChartBitmapHandler.resume(this.javaClass.simpleName)
    }

    protected fun canCreate(): Boolean {
        return !ChartBitmapHandler.isCreating()
    }

    protected fun hasBitmap(): Boolean {
        return ChartBitmapHandler.isRegistered(this.javaClass.simpleName)
    }

    protected fun hasIobCob(): Boolean {
        return style == Constants.WIDGET_STYLE_CHART_GLUCOSE_TREND_TIME_DELTA_IOB_COB || style == Constants.WIDGET_STYLE_GLUCOSE_TREND_TIME_DELTA_IOB_COB
    }

    @SuppressLint("SetTextI18n")
    protected fun createWallpaperView(color: Int? = null, backgroundColor: Int = Color.TRANSPARENT): Bitmap? {
        try {
            Log.d(LOG_ID, "Create wallpaper view for size $size and style $style")
            //getting the widget layout from xml using layout inflater
            val layout = if(style == Constants.WIDGET_STYLE_CHART_GLUCOSE_TREND_TIME_DELTA_IOB_COB)
                R.layout.floating_widget_chart
            else
                R.layout.floating_widget
            val lockscreenView = LayoutInflater.from(context).inflate(layout, null)
            val txtBgValue: TextView = lockscreenView.findViewById(R.id.glucose)
            val viewIcon: ImageView = lockscreenView.findViewById(R.id.trendImage)
            val txtDelta: TextView = lockscreenView.findViewById(R.id.deltaText)
            val txtTime: TextView = lockscreenView.findViewById(R.id.timeText)
            val txtIob: TextView = lockscreenView.findViewById(R.id.iobText)
            val txtCob: TextView = lockscreenView.findViewById(R.id.cobText)
            val graphImage: ImageView? = lockscreenView.findViewById(R.id.graphImage)

            lockscreenView.setBackgroundColor(backgroundColor)

            var textSize = DEFAULT_FONT_SIZE
            when(style) {
                Constants.WIDGET_STYLE_GLUCOSE_TREND_DELTA -> {
                    txtTime.visibility = GONE
                    txtDelta.visibility = VISIBLE
                    viewIcon.visibility = VISIBLE
                    txtIob.visibility = GONE
                    txtCob.visibility = GONE
                }
                Constants.WIDGET_STYLE_GLUCOSE_TREND -> {
                    txtTime.visibility = GONE
                    txtDelta.visibility = GONE
                    viewIcon.visibility = VISIBLE
                    txtIob.visibility = GONE
                    txtCob.visibility = GONE
                }
                Constants.WIDGET_STYLE_GLUCOSE -> {
                    txtTime.visibility = GONE
                    txtDelta.visibility = GONE
                    viewIcon.visibility = GONE
                    txtIob.visibility = GONE
                    txtCob.visibility = GONE
                }
                Constants.WIDGET_STYLE_CHART_GLUCOSE_TREND_TIME_DELTA_IOB_COB,
                Constants.WIDGET_STYLE_GLUCOSE_TREND_TIME_DELTA_IOB_COB -> {
                    textSize = 10f
                    txtTime.visibility = VISIBLE
                    txtDelta.visibility = VISIBLE
                    viewIcon.visibility = VISIBLE
                    txtIob.visibility = VISIBLE
                    txtCob.visibility = VISIBLE
                }
                else -> {
                    txtTime.visibility = VISIBLE
                    txtDelta.visibility = VISIBLE
                    viewIcon.visibility = VISIBLE
                    txtIob.visibility = GONE
                    txtCob.visibility = GONE
                }
            }

            txtBgValue.text = ReceiveData.getGlucoseAsString()
            txtBgValue.setTextColor(ReceiveData.getGlucoseColor())
            if (ReceiveData.isObsoleteShort() && !ReceiveData.isObsoleteLong()) {
                txtBgValue.paintFlags = Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                txtBgValue.paintFlags = 0
            }
            val imageSize = if(size == 1) 40 else if(size <= 5) 50 else if( size < 15) 100 else 200
            viewIcon.setImageIcon(BitmapUtils.getRateAsIcon(LOG_ID+"_trend", width = imageSize, height = imageSize))

            if(color == null) {
                txtTime.text =  "🕒 ${ReceiveData.getElapsedTimeMinuteAsString(context)}"
                if(graphImage != null)
                    txtDelta.text = "Δ  ${ReceiveData.getDeltaAsString()}"
                else
                    txtDelta.text = " Δ ${ReceiveData.getDeltaAsString()}"
                if(ReceiveData.iob.isNaN())
                    txtIob.visibility = GONE
                else
                    txtIob.text = "💉 ${ReceiveData.getIobAsString()}"
                if(ReceiveData.cob.isNaN())
                    txtCob.visibility = GONE
                else
                    txtCob.text = "🍔 ${ReceiveData.getCobAsString()}"
            } else {
                txtDelta.text = buildImageString(context, R.drawable.icon_delta, "Δ", "   ${ReceiveData.getDeltaAsString()}", color)
                txtTime.text = buildImageString(context, R.drawable.icon_clock, "🕒", "   ${ReceiveData.getElapsedTimeMinuteAsString(context)}", color)
                if(ReceiveData.iob.isNaN())
                    txtIob.visibility = GONE
                else
                    txtIob.text = buildImageString(context, R.drawable.icon_injection, "💉", " ${ReceiveData.getIobAsString()}", color)
                if(ReceiveData.cob.isNaN())
                    txtCob.visibility = GONE
                else
                    txtCob.text = buildImageString(context, R.drawable.icon_burger, "🍔", " ${ReceiveData.getCobAsString()}", color)
            }
            val usedSize = if(graphImage != null && (txtIob.visibility == VISIBLE || txtCob.visibility == VISIBLE)) size/2 else size
            val textUsedSize = if(graphImage != null && (txtIob.visibility == VISIBLE || txtCob.visibility == VISIBLE)) size*3/4 else size

            txtBgValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize+MIN_VALUE_SIZE+textUsedSize*VALUE_RESIZE_FACTOR)
            viewIcon.minimumWidth = Utils.dpToPx((MIN_SIZE+textUsedSize)*4f, context)
            txtDelta.setTextSize(TypedValue.COMPLEX_UNIT_SP, minOf(MIN_SIZE+usedSize*2f, MAX_SIZE))
            txtTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, minOf(MIN_SIZE+usedSize*2f, MAX_SIZE))
            txtIob.setTextSize(TypedValue.COMPLEX_UNIT_SP, minOf(MIN_SIZE+usedSize*2f, MAX_SIZE))
            txtCob.setTextSize(TypedValue.COMPLEX_UNIT_SP, minOf(MIN_SIZE+usedSize*2f, MAX_SIZE))


            if(graphImage != null) {
                val chart = getChart()
                if(chart != null) {
                    graphImage.visibility = VISIBLE
                    lockscreenView.measure(
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    Log.d(LOG_ID, "Mesasured width ${lockscreenView.measuredWidth} and height ${lockscreenView.measuredHeight} for chart")

                    graphImage.setImageBitmap(chart)
                    graphImage.layoutParams.height = lockscreenView.measuredWidth/3
                    graphImage.requestLayout()
                } else {
                    Log.d(LOG_ID, "No chart available")
                    graphImage.visibility = GONE
                }
            }

            lockscreenView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            lockscreenView.layout(0, 0, lockscreenView.measuredWidth, lockscreenView.measuredHeight)

            Log.d(LOG_ID, "Mesasured width ${lockscreenView.measuredWidth} and height ${lockscreenView.measuredHeight}")


            color?.let { col ->
                txtBgValue.setTextColor(col)
                viewIcon.setColorFilter(col)
                graphImage?.setColorFilter(col)
            }

            if(curWallpaper == null || curWallpaper!!.width != lockscreenView.width || curWallpaper!!.height != lockscreenView.height) {
                receycleOldWallpaper()
                Log.i(LOG_ID, "Create new bitmap with size ${lockscreenView.width} x ${lockscreenView.height}")
                oldWallpaper = curWallpaper
                curWallpaper = Bitmap.createBitmap(lockscreenView.width, lockscreenView.height, Bitmap.Config.ARGB_8888)
            } else {
                Log.d(LOG_ID, "Reuse old bitmap")
                BitmapUtils.clearBitmap(curWallpaper!!)
            }
            val canvas = Canvas(curWallpaper!!)
            lockscreenView.draw(canvas)
            return curWallpaper
        } catch (exc: Exception) {
            Log.e(LOG_ID, "createWallpaperView exception: " + exc.message.toString())
        }
        return null
    }

    private fun buildImageString(context: Context, res: Int, emoji: String, text: String, colour: Int? = null): SpannableStringBuilder {
        val spannable = SpannableStringBuilder("")
        if (colour == null) {
            spannable.append(emoji + text)
        }
        else {
            spannable.append(" $text")
            val drawable: Drawable? = ContextCompat.getDrawable(context, res)
            if (drawable != null) {
                DrawableCompat.setTint(drawable, colour)
                drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
                spannable.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BASELINE), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(ForegroundColorSpan(colour), 1, spannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return spannable
    }
}