package com.appmire.gpsinfo.data.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.appmire.gpsinfo.R

@Immutable
enum class FixStatus(@StringRes val labelRes: Int) {
    NO_FIX(R.string.fix_status_no_fix),
    TWO_D(R.string.fix_status_2d),
    THREE_D(R.string.fix_status_3d),
}
