package org.acme.util;

import org.acme.api.dto.PlanningRequest;
import org.acme.converter.EmployeeScheduleBuilder;
import org.acme.model.EmployeeSchedule;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * PlanningRequest를 EmployeeSchedule로 변환하는 유틸리티 클래스.
 * 내부적으로 EmployeeScheduleBuilder를 위임합니다.
 * @deprecated 대신 {@link EmployeeScheduleBuilder}를 직접 사용하세요.
 */
@ApplicationScoped
@Deprecated(forRemoval = false)
public class DtoConverter {

    @Inject
    EmployeeScheduleBuilder employeeScheduleBuilder;

    /**
     * PlanningRequest를 EmployeeSchedule로 변환합니다.
     *
     * @param request 계획 요청
     * @return 변환된 직원 스케줄
     * @deprecated 대신 {@link EmployeeScheduleBuilder#build(PlanningRequest)}를 사용하세요.
     */
    @Deprecated
    public static EmployeeSchedule toEmployeeSchedule(PlanningRequest request) {
        // 임시: 내부에서 새로운 빌더를 생성하여 사용
        // TODO: @Inject된 인스턴스를 사용하도록 리팩토링 필요
        EmployeeScheduleBuilder builder = new EmployeeScheduleBuilder();
        return builder.build(request);
    }

    /**
     * PlanningRequest를 EmployeeSchedule로 변환합니다.
     * 인스턴스 메서드 버전으로, Inject된 EmployeeScheduleBuilder를 사용합니다.
     *
     * @param request 계획 요청
     * @return 변환된 직원 스케줄
     */
    public EmployeeSchedule convert(PlanningRequest request) {
        return employeeScheduleBuilder.build(request);
    }
}
