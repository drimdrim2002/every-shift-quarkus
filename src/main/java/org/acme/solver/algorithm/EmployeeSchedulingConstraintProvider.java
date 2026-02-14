package org.acme.solver.algorithm;

import java.time.Duration;
import java.time.LocalDateTime;

import org.acme.model.Availability;
import org.acme.model.AvailabilityType;
import org.acme.model.Shift;
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
                return new Constraint[] {
                                // Hard constraints
                                requiredSkill(constraintFactory),
                                noOverlappingShifts(constraintFactory),
                                atLeast12HoursBetweenTwoShifts(constraintFactory),
                                oneShiftPerDay(constraintFactory),
                                unavailableEmployee(constraintFactory),
                                // Soft constraints (우선순위: undesired > fair > desired)
                                undesiredDayForEmployee(constraintFactory),
                                fairShiftDistribution(constraintFactory),
                                desiredDayForEmployee(constraintFactory)
                };
        }

        Constraint requiredSkill(ConstraintFactory constraintFactory) {
                return constraintFactory.forEach(Shift.class)
                                .filter(shift -> !shift.getEmployee().getSkillSet().contains(shift.getRequiredSkill()))
                                .penalize(ONE_HARD)
                                .asConstraint("Missing required skill");
        }

        Constraint noOverlappingShifts(ConstraintFactory constraintFactory) {
                return constraintFactory.forEachUniquePair(
                                Shift.class,
                                Joiners.equal(Shift::getEmployee),
                                Joiners.overlapping(Shift::getStart, Shift::getEnd))
                                .penalize(ONE_HARD, EmployeeSchedulingConstraintProvider::getMinuteOverlap)
                                .asConstraint("Overlapping shift");
        }

        Constraint atLeast12HoursBetweenTwoShifts(ConstraintFactory constraintFactory) {
                return constraintFactory.forEachUniquePair(
                                Shift.class,
                                Joiners.equal(Shift::getEmployee),
                                Joiners.lessThanOrEqual(Shift::getEnd, Shift::getStart))
                                .filter((firstShift,
                                                secondShift) -> Duration
                                                                .between(firstShift.getEnd(), secondShift.getStart())
                                                                .toHours() < MIN_SHIFT_HOURS)
                                .penalize(ONE_HARD, (firstShift, secondShift) -> {
                                        int breakLength = (int) Duration
                                                        .between(firstShift.getEnd(), secondShift.getStart())
                                                        .toMinutes();
                                        return (MIN_SHIFT_HOURS * 60) - breakLength;
                                })
                                .asConstraint("At least 12 hours between 2 shifts");
        }

        Constraint oneShiftPerDay(ConstraintFactory constraintFactory) {
                return constraintFactory.forEachUniquePair(
                                Shift.class,
                                Joiners.equal(Shift::getEmployee),
                                Joiners.equal(shift -> shift.getStart().toLocalDate()))
                                .penalize(ONE_HARD)
                                .asConstraint("Max one shift per day");
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
                                .join(Availability.class,
                                                Joiners.equal((Shift shift) -> shift.getStart().toLocalDate(),
                                                                Availability::getDate),
                                                Joiners.equal(Shift::getEmployee, Availability::getEmployee))
                                .filter((shift, availability) -> availability
                                                .getAvailabilityType() == AvailabilityType.UNDESIRED)
                                .penalize(ONE_SOFT_UNDESIRED, (shift, availability) -> getShiftDurationInMinutes(shift))
                                .asConstraint("Undesired day for employee");
        }

        Constraint fairShiftDistribution(ConstraintFactory constraintFactory) {
                return constraintFactory.forEach(Shift.class)
                                .groupBy(Shift::getEmployee, ConstraintCollectors.count())
                                .penalize(ONE_SOFT_FAIR, (employee, shiftCount) -> shiftCount * shiftCount)
                                .asConstraint("Fair shift distribution");
        }
}
