package be.appmire.gpsinfo.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Single-line Text that shrinks its font size to fit the available
 * width. Starts at [maxFontSize], steps down by [stepRatio] each
 * layout pass that overflows, capped at [minFontSize]. Stable on the
 * second or third frame for any reasonable text — Compose elides
 * intermediate frames so the user typically sees only the final
 * size.
 *
 * Use cases: a metric cell whose label is 90 % of the time short
 * ("12") but occasionally long ("123") and must never wrap. Cf.
 * `Text(maxLines = 1, softWrap = false)` which clips instead.
 *
 * The font size is keyed on [text] so changing the displayed value
 * resets the size — important when the cell flips from "—" back to
 * a measurement.
 */
@Composable
fun AutoSizingText(
    text: String,
    modifier: Modifier = Modifier,
    maxFontSize: TextUnit,
    minFontSize: TextUnit = 12.sp,
    color: Color = Color.Unspecified,
    fontFamily: FontFamily? = null,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    style: TextStyle = LocalTextStyle.current,
    stepRatio: Float = 0.92f,
) {
    var fontSize by remember(text, maxFontSize) { mutableStateOf(maxFontSize) }
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontFamily = fontFamily,
        fontWeight = fontWeight,
        fontSize = fontSize,
        textAlign = textAlign,
        style = style,
        maxLines = 1,
        softWrap = false,
        onTextLayout = { result ->
            if (result.didOverflowWidth || result.didOverflowHeight) {
                val shrunk = (fontSize.value * stepRatio).sp
                if (shrunk.value >= minFontSize.value) fontSize = shrunk
            }
        },
    )
}
