package org.acme.solver.output;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.acme.model.EmployeeSchedule;
import org.acme.model.Shift;
import org.slf4j.Logger;

import io.quarkus.runtime.LaunchMode;

/**
 * 스케줄을 로그로 출력하는 유틸리티 클래스.
 * 중복 코드 제거를 위해 추출되었습니다.
 */
public class SchedulePrinter {

    /**
     * 로컬 실행 여부를 반환합니다.
     * DEVELOPMENT/TEST 모드만 로컬로 간주합니다.
     */
    public static boolean isLocalLaunchMode() {
        LaunchMode launchMode = LaunchMode.current();
        return launchMode == LaunchMode.DEVELOPMENT || launchMode == LaunchMode.TEST;
    }

    /**
     * 스케줄을 로그로 출력합니다.
     *
     * @param schedule 출력할 스케줄
     * @param logger   사용할 로거
     */
    public static void printSchedule(EmployeeSchedule schedule, Logger logger) {
        // dev 또는 test 모드에서만 실행
        if (!isLocalLaunchMode()) {
            return;
        }

        logger.info("\n--- Schedule ---");
        Map<LocalDate, List<Shift>> shiftsByDate = schedule.getShiftList().stream()
                .collect(Collectors.groupingBy(shift -> shift.getStart().toLocalDate()));

        shiftsByDate.keySet().stream().sorted().forEach(date -> {
            logger.info("Date: {}", date);
            for (Shift shift : shiftsByDate.get(date)) {
                logger.info("  {} - {} ({}): {}",
                        shift.getStart().toLocalTime(),
                        shift.getEnd().toLocalTime(),
                        shift.getLocation(),
                        shift.getEmployee() == null ? "UNASSIGNED" : shift.getEmployee().getName());
            }
        });
    }
}
