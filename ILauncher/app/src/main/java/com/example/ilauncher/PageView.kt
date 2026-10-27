package com.example.ilauncher

import android.content.Context
import android.view.View
import android.view.ViewGroup

class CellLP(val row: Int, val col: Int, val sc: Int, val sr: Int) :
    ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)

/** Página com grade COLS x ROWS; cada filho ocupa uma ou mais células. */
class PageView(ctx: Context) : ViewGroup(ctx) {
    var cw = 1
    var ch = 1

    override fun checkLayoutParams(p: LayoutParams?) = p is CellLP

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = View.MeasureSpec.getSize(wSpec)
        val h = View.MeasureSpec.getSize(hSpec)
        setMeasuredDimension(w, h)
        cw = ((w - paddingLeft - paddingRight) / COLS).coerceAtLeast(1)
        ch = ((h - paddingTop) / ROWS).coerceAtLeast(1)
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val lp = v.layoutParams as CellLP
            v.measure(
                View.MeasureSpec.makeMeasureSpec(cw * lp.sc, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(ch * lp.sr, View.MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val lp = v.layoutParams as CellLP
            val x = paddingLeft + lp.col * cw
            val y = paddingTop + lp.row * ch
            v.layout(x, y, x + cw * lp.sc, y + ch * lp.sr)
        }
    }
}
