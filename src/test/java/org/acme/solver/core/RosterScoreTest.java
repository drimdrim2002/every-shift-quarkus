package org.acme.solver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

class RosterScoreTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void compareToUsesHardThenSoftLexicographicPriority() {
        assertTrue(score(0, -10_000, -10_000, -10_000, -10_000)
                .compareTo(score(-1, 10_000, 10_000, 10_000, 10_000)) > 0);

        for (int priority = 0; priority < RosterScore.SOFT_LEVELS; priority++) {
            int[] better = { 0, 0, 0, 0 };
            int[] worse = { 0, 0, 0, 0 };
            better[priority] = 1;
            for (int lower = priority + 1; lower < RosterScore.SOFT_LEVELS; lower++) {
                better[lower] = Integer.MIN_VALUE;
                worse[lower] = Integer.MAX_VALUE;
            }
            assertTrue(new RosterScore(0, better).compareTo(new RosterScore(0, worse)) > 0);
        }
    }

    @Test
    void equalityAndHashCodeUseEveryLevel() {
        RosterScore score = score(0, -1, -2, -3, 4);

        assertEquals(score, score(0, -1, -2, -3, 4));
        assertEquals(score.hashCode(), score(0, -1, -2, -3, 4).hashCode());
        assertNotEquals(score, score(0, -1, -2, -3, 5));
        assertTrue(score.isFeasible());
        assertFalse(score(-1, 0, 0, 0, 0).isFeasible());
    }

    @Test
    void constructorAndAccessorsNeverExposeInternalArray() {
        int[] source = { 1, 2, 3, 4 };
        RosterScore score = new RosterScore(0, source);
        source[0] = 999;

        assertEquals(1, score.softScore(0));
        assertThrows(UnsupportedOperationException.class, () -> score.softScores().set(0, 999));
        assertEquals(List.of(1, 2, 3, 4), score.softScores());
    }

    @Test
    void deltaPromotesOperandsToLongBeforeSubtraction() {
        RosterScore maximum = score(Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0, 0);
        RosterScore minimum = score(Integer.MIN_VALUE, Integer.MIN_VALUE, 0, 0, 0);

        assertEquals(4_294_967_295L, maximum.hardDeltaFrom(minimum));
        assertEquals(4_294_967_295L, maximum.softDeltaFrom(minimum, 0));
        assertEquals(-4_294_967_295L, minimum.softDeltaFrom(maximum, 0));
    }

    @Test
    void rejectsWrongLevelCountAndOverflowInput() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> RosterScore.of(0, 1, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> RosterScore.of(0, 1, 2, 3, 4, 5));

        assertThrows(JsonMappingException.class, () -> objectMapper.readValue(
                "{\"hardScore\":2147483648,\"softScores\":[0,0,0,0]}", RosterScore.class));
        assertThrows(JsonMappingException.class, () -> objectMapper.readValue(
                "{\"hardScore\":0,\"softScores\":[0,0,-2147483649,0]}", RosterScore.class));
    }

    @Test
    void jsonRoundTripIsStableAndUsesNoArrayAccessor() throws Exception {
        RosterScore original = score(0, -30, -120, -5409, 240);

        String json = objectMapper.writeValueAsString(original);
        RosterScore restored = objectMapper.readValue(json, RosterScore.class);

        assertEquals("{\"hardScore\":0,\"softScores\":[-30,-120,-5409,240]}", json);
        assertEquals(original, restored);
        assertEquals("[0]hard/[-30/-120/-5409/240]soft", restored.toString());
    }

    private static RosterScore score(int hard, int soft0, int soft1, int soft2, int soft3) {
        return RosterScore.of(hard, soft0, soft1, soft2, soft3);
    }
}
