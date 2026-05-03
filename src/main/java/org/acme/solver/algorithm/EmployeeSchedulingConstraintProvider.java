package org.acme.solver.algorithm;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Locale;

import org.acme.model.Availability;
import org.acme.model.AvailabilityType;
import org.acme.model.Shift;
import org.acme.solver.ShiftDateMatcher;
import org.optaplanner.core.api.score.buildin.bendable.BendableScore;
import org.optaplanner.core.api.score.stream.Constraint;
import org.optaplanner.core.api.score.stream.ConstraintCollectors;
import org.optaplanner.core.api.score.stream.ConstraintFactory;
import org.optaplanner.core.api.score.stream.ConstraintProvider;
import org.optaplanner.core.api.score.stream.Joiners;

public class EmployeeSchedulingConstraintProvider implements ConstraintProvider {

    private static final int HARD_LEVELS = 1;
    private static final int SOFT_LEVELS = 3;

    private static final int HARD_LEVEL_INDEX = 0;
    private static final int SOFT_UNDESIRED_INDEX = 0;
    private static final int SOFT_FAIR_INDEX = 1;
    private static final int SOFT_DESIRED_INDEX = 2;

    private static final BendableScore ONE_HARD = BendableScore.ofHard(HARD_LEVELS, SOFT_LEVELS, HARD_LEVEL_INDEX,
            1);
    private static final BendableScore ONE_SOFT_UNDESIRED = BendableScore.ofSoft(HARD_LEVELS, SOFT_LEVELS,
            SOFT_UNDESIRED_INDEX, 1);
    private static final BendableScore ONE_SOFT_FAIR = BendableScore.ofSoft(HARD_LEVELS, SOFT_LEVELS,
            SOFT_FAIR_INDEX, 1);
    private static final BendableScore ONE_SOFT_DESIRED = BendableScore.ofSoft(HARD_LEVELS, SOFT_LEVELS,
            SOFT_DESIRED_INDEX, 1);

    private static final int MIN_SHIFT_HOURS = 12;
    private static final int MIN_NIGHT_TO_NEXT_DAY_REST_MINUTES = 32 * 60;
    private static final int MIN_REST_AFTER_TWO_CONSECUTIVE_NIGHTS_MINUTES = 48 * 60;
    private static final int MAX_MONTHLY_NIGHT_SHIFTS = 15;
    private static final String SHIFT_TYPE_DAY = "D";
    private static final String SHIFT_TYPE_EVENING = "E";
    private static final String SHIFT_TYPE_NIGHT = "N";
    private static final String SHIFT_TYPE_UNKNOWN = "UNKNOWN";

    private static final int FAIR_WEIGHT_DAY = 1;
    private static final int FAIR_WEIGHT_EVENING = 5;
    private static final int FAIR_WEIGHT_NIGHT = 10;

    public static int getMinuteOverlap(Shift shift1, Shift shift2) {
        // The overlap of two timeslot occurs in the range common to both timeslots.
        // Both timeslots are active after the higher of their two start times,
        // and before the lower of their two end times.
        LocalDateTime shift1Start = shift1.getStart();
        LocalDateTime shift1End = shift1.getEnd();
        LocalDateTime shift2Start = shift2.getStart();
        LocalDateTime shift2End = shift2.getEnd();
        return (int) Duration.between((shift1Start.isAfter(shift2Start)) ? shift1Start : shift2Start,
                (shift1End.isBefore(shift2End)) ? shift1End : shift2End).toMinutes();
    }

    private static int getShiftDurationInMinutes(Shift shift) {
        return (int) Duration.between(shift.getStart(), shift.getEnd()).toMinutes();
    }

    @Override
    public Constraint[] defineConstraints(ConstraintFactory constraintFactory) {
        return new Constraint[]{
                // Hard constraints
                requiredSkill(constraintFactory), noOverlappingShifts(constraintFactory),
                atLeast12HoursBetweenTwoShifts(constraintFactory),
                noThreeConsecutiveNightShifts(constraintFactory),
                atLeast32HoursFromNightToNextDayShift(constraintFactory),
                atLeast48HoursAfterTwoConsecutiveNightShifts(constraintFactory),
                max15NightShiftsPerMonth(constraintFactory), oneShiftPerDay(constraintFactory),
                unavailableEmployee(constraintFactory),
                // Soft constraints (우선순위: undesired > fair > desired)
                undesiredDayForEmployee(constraintFactory), fairShiftDistribution(constraintFactory),
                desiredDayForEmployee(constraintFactory)};
    }

    Constraint requiredSkill(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .filter(shift -> !shift.getEmployee().getSkillSet().contains(shift.getRequiredSkill()))
                .penalize(ONE_HARD).asConstraint("Missing required skill");
    }

    Constraint noOverlappingShifts(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEachUniquePair(Shift.class, Joiners.equal(Shift::getEmployee),
                        Joiners.overlapping(Shift::getStart, Shift::getEnd))
                .penalize(ONE_HARD, EmployeeSchedulingConstraintProvider::getMinuteOverlap)
                .asConstraint("Overlapping shift");
    }

    Constraint atLeast12HoursBetweenTwoShifts(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEachUniquePair(Shift.class, Joiners.equal(Shift::getEmployee))
                .filter((firstShift, secondShift) -> {
                    int breakLength = getBreakLengthInMinutes(firstShift, secondShift);
                    return breakLength >= 0 && breakLength < MIN_SHIFT_HOURS * 60;
                })
                .penalize(ONE_HARD, (firstShift, secondShift) -> {
                    int breakLength = getBreakLengthInMinutes(firstShift, secondShift);
                    return (MIN_SHIFT_HOURS * 60) - breakLength;
                }).asConstraint("At least 12 hours between 2 shifts");
    }

    Constraint noThreeConsecutiveNightShifts(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .filter(EmployeeSchedulingConstraintProvider::isNightShift)
                .join(Shift.class, Joiners.equal(Shift::getEmployee))
                .filter((firstNight, secondNight) -> isNightShift(secondNight)
                        && getNightLogicalDate(secondNight).equals(getNightLogicalDate(firstNight).plusDays(1)))
                .join(Shift.class,
                        Joiners.equal((firstNight, secondNight) -> firstNight.getEmployee(), Shift::getEmployee))
                .filter((firstNight, secondNight, thirdNight) -> isNightShift(thirdNight)
                        && getNightLogicalDate(thirdNight).equals(getNightLogicalDate(firstNight).plusDays(2)))
                .penalize(ONE_HARD)
                .asConstraint("No three consecutive night shifts");
    }

    Constraint atLeast32HoursFromNightToNextDayShift(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .filter(EmployeeSchedulingConstraintProvider::isNightShift)
                .join(Shift.class,
                        Joiners.equal(Shift::getEmployee),
                        Joiners.lessThan(Shift::getEnd, Shift::getStart))
                .filter((nightShift, dayShift) -> isDayShift(dayShift))
                .groupBy((Shift nightShift, Shift dayShift) -> nightShift,
                        ConstraintCollectors.<Shift, Shift, LocalDateTime>min(
                                (Shift nightShift, Shift dayShift) -> dayShift.getStart()))
                .filter((nightShift, nextDayStart) -> getMinutesBetween(nightShift.getEnd(), nextDayStart)
                        < MIN_NIGHT_TO_NEXT_DAY_REST_MINUTES)
                .penalize(ONE_HARD,
                        (nightShift, nextDayStart) -> MIN_NIGHT_TO_NEXT_DAY_REST_MINUTES
                                - getMinutesBetween(nightShift.getEnd(), nextDayStart))
                .asConstraint("At least 32 hours from night to next day shift");
    }

    Constraint atLeast48HoursAfterTwoConsecutiveNightShifts(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .filter(EmployeeSchedulingConstraintProvider::isNightShift)
                .join(Shift.class, Joiners.equal(Shift::getEmployee))
                .filter((firstNight, secondNight) -> isNightShift(secondNight)
                        && getNightLogicalDate(secondNight).equals(getNightLogicalDate(firstNight).plusDays(1)))
                .join(Shift.class,
                        Joiners.equal((firstNight, secondNight) -> firstNight.getEmployee(), Shift::getEmployee))
                .filter((firstNight, secondNight, nextShift) -> !nextShift.getStart().isBefore(secondNight.getEnd()))
                .groupBy((firstNight, secondNight, nextShift) -> secondNight,
                        ConstraintCollectors.<Shift, Shift, Shift, LocalDateTime>min(
                                (firstNight, secondNight, nextShift) -> nextShift.getStart()))
                .filter((secondNight, nextShiftStart) -> getMinutesBetween(secondNight.getEnd(), nextShiftStart)
                        < MIN_REST_AFTER_TWO_CONSECUTIVE_NIGHTS_MINUTES)
                .penalize(ONE_HARD,
                        (secondNight, nextShiftStart) -> MIN_REST_AFTER_TWO_CONSECUTIVE_NIGHTS_MINUTES
                                - getMinutesBetween(secondNight.getEnd(), nextShiftStart))
                .asConstraint("At least 48 hours after two consecutive night shifts");
    }

    Constraint max15NightShiftsPerMonth(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .filter(EmployeeSchedulingConstraintProvider::isNightShift)
                .groupBy(Shift::getEmployee, shift -> YearMonth.from(shift.getStart()), ConstraintCollectors.count())
                .filter((employee, month, nightShiftCount) -> nightShiftCount > MAX_MONTHLY_NIGHT_SHIFTS)
                .penalize(ONE_HARD,
                        (employee, month, nightShiftCount) -> nightShiftCount - MAX_MONTHLY_NIGHT_SHIFTS)
                .asConstraint("Max 15 night shifts per month");
    }

    Constraint oneShiftPerDay(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEachUniquePair(Shift.class, Joiners.equal(Shift::getEmployee),
                        Joiners.equal(shift -> shift.getStart().toLocalDate()))
                .penalize(ONE_HARD).asConstraint("Max one shift per day");
    }

    Constraint unavailableEmployee(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .join(Availability.class,
                        Joiners.equal((Shift shift) -> shift.getStart().toLocalDate(),
                                Availability::getDate),
                        Joiners.equal(Shift::getEmployee, Availability::getEmployee))
                .filter((shift, availability) -> availability
                        .getAvailabilityType() == AvailabilityType.UNAVAILABLE)
                .penalize(ONE_HARD, (shift, availability) -> getShiftDurationInMinutes(shift))
                .asConstraint("Unavailable employee");
    }

    Constraint desiredDayForEmployee(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .join(Availability.class,
                        Joiners.equal((Shift shift) -> shift.getStart().toLocalDate(),
                                Availability::getDate),
                        Joiners.equal(Shift::getEmployee, Availability::getEmployee))
                .filter((shift, availability) -> availability
                        .getAvailabilityType() == AvailabilityType.DESIRED)
                .reward(ONE_SOFT_DESIRED, (shift, availability) -> getShiftDurationInMinutes(shift))
                .asConstraint("Desired day for employee");
    }

    Constraint undesiredDayForEmployee(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .filter(shift -> !shift.isPinned())
                .join(Availability.class,
                        Joiners.equal(Shift::getEmployee, Availability::getEmployee))
                .filter((shift, availability) -> availability
                        .getAvailabilityType() == AvailabilityType.UNDESIRED)
                .filter((shift, availability) -> ShiftDateMatcher
                        .matchesActualOrLogicalDate(shift, availability.getDate()))
                // A shift is penalized at most once, even if multiple undesired dates match.
                .groupBy((shift, availability) -> shift)
                .penalize(ONE_SOFT_UNDESIRED, EmployeeSchedulingConstraintProvider::getShiftDurationInMinutes)
                .asConstraint("Undesired day for employee");
    }

    Constraint fairShiftDistribution(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .groupBy(Shift::getEmployee, EmployeeSchedulingConstraintProvider::resolveShiftType,
                        ConstraintCollectors.count())
                .penalize(ONE_SOFT_FAIR,
                        (employee, shiftType, shiftCount) -> getFairWeightByShiftType(shiftType)
                                * shiftCount * shiftCount)
                .asConstraint("Fair shift distribution");
    }

    private static String resolveShiftType(Shift shift) {
        if (shift.getShiftCode() == null) {
            return SHIFT_TYPE_UNKNOWN;
        }

        String normalized = shift.getShiftCode().trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return SHIFT_TYPE_UNKNOWN;
        }
        return normalized;
    }

    private static int getFairWeightByShiftType(String shiftType) {
        return switch (shiftType) {
            case SHIFT_TYPE_NIGHT -> FAIR_WEIGHT_NIGHT;
            case SHIFT_TYPE_EVENING -> FAIR_WEIGHT_EVENING;
            case SHIFT_TYPE_DAY -> FAIR_WEIGHT_DAY;
            default -> FAIR_WEIGHT_DAY;
        };
    }

    private static int getBreakLengthInMinutes(Shift firstShift, Shift secondShift) {
        if (!firstShift.getEnd().isAfter(secondShift.getStart())) {
            return (int) Duration.between(firstShift.getEnd(), secondShift.getStart()).toMinutes();
        }

        if (!secondShift.getEnd().isAfter(firstShift.getStart())) {
            return (int) Duration.between(secondShift.getEnd(), firstShift.getStart()).toMinutes();
        }

        return -1;
    }

    private static int getMinutesBetween(LocalDateTime from, LocalDateTime to) {
        return (int) Duration.between(from, to).toMinutes();
    }

    private static LocalDate getNightLogicalDate(Shift shift) {
        // Night logical-date policy (including cutoff time) is centralized in ShiftDateMatcher.
        // If the cutoff changes, re-verify undesired matching and consecutive-night constraints.
        return ShiftDateMatcher.resolveLogicalDate(shift);
    }

    private static boolean isNightShift(Shift shift) {
        return SHIFT_TYPE_NIGHT.equals(resolveShiftType(shift));
    }

    private static boolean isDayShift(Shift shift) {
        return SHIFT_TYPE_DAY.equals(resolveShiftType(shift));
    }
}
