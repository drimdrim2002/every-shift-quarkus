package org.acme.solver.move;

import java.util.Objects;

import org.acme.solver.core.RosterSolution;

/**
 * assignment 순서와 문제 크기를 포함하는 128-bit 로스터 fingerprint입니다.
 * 두 lane은 서로 다른 결합 방식(XOR, 합)을 사용하며 move마다 O(1)로 갱신됩니다.
 */
public record SolutionFingerprint(long xorLane, long sumLane, int employeeCount, int shiftCount) {

    public static SolutionFingerprint from(RosterSolution solution) {
        Objects.requireNonNull(solution, "solution");
        long xor = shapeHash(solution.employeeCount(), solution.shiftCount());
        long sum = Long.rotateLeft(xor, 23);
        for (int shiftIndex = 0; shiftIndex < solution.shiftCount(); shiftIndex++) {
            int employeeIndex = solution.employeeIndex(shiftIndex);
            xor ^= assignmentHash(shiftIndex, employeeIndex, 0x9E3779B97F4A7C15L);
            sum += assignmentHash(shiftIndex, employeeIndex, 0xD1B54A32D192ED03L);
        }
        return new SolutionFingerprint(xor, sum, solution.employeeCount(), solution.shiftCount());
    }

    public SolutionFingerprint replace(int shiftIndex, int oldEmployeeIndex, int newEmployeeIndex) {
        validateShiftIndex(shiftIndex);
        validateEmployeeIndex(oldEmployeeIndex);
        validateEmployeeIndex(newEmployeeIndex);
        if (oldEmployeeIndex == newEmployeeIndex) {
            return this;
        }
        long oldXor = assignmentHash(shiftIndex, oldEmployeeIndex, 0x9E3779B97F4A7C15L);
        long newXor = assignmentHash(shiftIndex, newEmployeeIndex, 0x9E3779B97F4A7C15L);
        long oldSum = assignmentHash(shiftIndex, oldEmployeeIndex, 0xD1B54A32D192ED03L);
        long newSum = assignmentHash(shiftIndex, newEmployeeIndex, 0xD1B54A32D192ED03L);
        return new SolutionFingerprint(
                xorLane ^ oldXor ^ newXor,
                sumLane - oldSum + newSum,
                employeeCount,
                shiftCount);
    }

    @Override
    public String toString() {
        return "%016x%016x/%d/%d".formatted(xorLane, sumLane, employeeCount, shiftCount);
    }

    private void validateShiftIndex(int shiftIndex) {
        if (shiftIndex < 0 || shiftIndex >= shiftCount) {
            throw new IndexOutOfBoundsException("shiftIndex 범위를 벗어났습니다: " + shiftIndex);
        }
    }

    private void validateEmployeeIndex(int employeeIndex) {
        if (employeeIndex < 0 || employeeIndex >= employeeCount) {
            throw new IndexOutOfBoundsException("employeeIndex 범위를 벗어났습니다: " + employeeIndex);
        }
    }

    private static long shapeHash(int employeeCount, int shiftCount) {
        return mix64(((long) employeeCount << 32) ^ Integer.toUnsignedLong(shiftCount));
    }

    private static long assignmentHash(int shiftIndex, int employeeIndex, long seed) {
        long value = (Integer.toUnsignedLong(shiftIndex) << 32)
                ^ Integer.toUnsignedLong(employeeIndex)
                ^ seed;
        return mix64(value);
    }

    private static long mix64(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
