package be.appmire.gpsinfo.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CardinalTest {

    @Test fun cardinal_north_at_zero() = assertEquals("N", headingToCardinal(0f))
    @Test fun cardinal_north_at_360() = assertEquals("N", headingToCardinal(360f))
    @Test fun cardinal_east() = assertEquals("E", headingToCardinal(90f))
    @Test fun cardinal_south() = assertEquals("S", headingToCardinal(180f))
    @Test fun cardinal_west() = assertEquals("W", headingToCardinal(270f))
    @Test fun cardinal_ene_at_60() = assertEquals("ENE", headingToCardinal(60f))
    @Test fun cardinal_nne_at_30() = assertEquals("NNE", headingToCardinal(30f))

    // Boundary just below the wrap point — 11.24 rounds toward N, 11.26 toward NNE.
    @Test fun cardinal_boundary_low() = assertEquals("N", headingToCardinal(11.24f))
    @Test fun cardinal_boundary_high() = assertEquals("NNE", headingToCardinal(11.26f))

    // Negative input is normalised
    @Test fun cardinal_handles_negative() = assertEquals("N", headingToCardinal(-1f))
    @Test fun cardinal_handles_over_360() = assertEquals("N", headingToCardinal(721f))
}
