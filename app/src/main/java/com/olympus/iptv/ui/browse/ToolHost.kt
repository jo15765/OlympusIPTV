package com.olympus.iptv.ui.browse

import androidx.fragment.app.Fragment

interface ToolHost {
    fun showTool(fragment: Fragment)
    fun hideTool()
    fun isToolVisible(): Boolean
}