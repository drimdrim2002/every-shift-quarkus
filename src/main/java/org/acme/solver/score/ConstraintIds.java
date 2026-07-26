package org.acme.solver.score;

import java.util.List;

/**
 * Constraint Streams의 constraint name과 동일한 안정 ID입니다.
 */
public final class ConstraintIds {

    public static final String REQUIRED_SKILL = "Missing required skill";
    public static final String OVERLAP = "Overlapping shift";
    public static final String MINIMUM_REST = "At least 12 hours between 2 shifts";
    public static final String CONSECUTIVE_NIGHT = "No four consecutive night shifts";
    public static final String MONTHLY_NIGHT_LIMIT = "Max 15 night shifts per month";
    public static final String ONE_SHIFT_PER_DAY = "Max one shift per day";
    public static final String PRECEPTEE_PAIR = "Preceptee must work same shift as preceptor";
    public static final String PRECEPTOR_PAIR = "Preceptor must work same shift as preceptee";
    public static final String POST_NIGHT_RECOVERY = "At least 48 hours after two or more consecutive night shifts";
    public static final String NIGHT_TO_DAY_REST = "At least 32 hours from night to next day shift";
    public static final String UNDESIRED = "Undesired day for employee";
    public static final String NIGHT_FAIRNESS = "Night shift fairness";
    public static final String HOLIDAY_FAIRNESS = "Holiday burden fairness";
    public static final String DAY_EVENING_FAIRNESS = "Day/evening shift fairness";
    public static final String DESIRED = "Desired day for employee";

    public static final List<String> ALL = List.of(
            REQUIRED_SKILL,
            OVERLAP,
            MINIMUM_REST,
            CONSECUTIVE_NIGHT,
            MONTHLY_NIGHT_LIMIT,
            ONE_SHIFT_PER_DAY,
            PRECEPTEE_PAIR,
            PRECEPTOR_PAIR,
            POST_NIGHT_RECOVERY,
            NIGHT_TO_DAY_REST,
            UNDESIRED,
            NIGHT_FAIRNESS,
            HOLIDAY_FAIRNESS,
            DAY_EVENING_FAIRNESS,
            DESIRED);

    private ConstraintIds() {
    }
}
