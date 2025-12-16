package org.acme.resource;

import io.quarkus.logging.Log;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import org.acme.api.dto.PlanningRequest;

@Path("/worker")
public class WorkerResource {

    @POST
    @Path("/process")
    @Consumes(MediaType.APPLICATION_JSON)
    public void processEngineTask(PlanningRequest requestDto) {

        Log.info("작업 시작: " + requestDto.organization().name());

        // 1. 도메인 로직 실행 (OptaPlanner / OR-Tools 등)
        // solverService.solve(requestDto);

        // 날짜별 요구사항 확인 예시
        requestDto.requirements().forEach((date, reqMap) -> {
            Log.info("날짜: " + date + ", 필요 인원: " + reqMap.get("total"));
        });

        Log.info("작업 완료");

    }
}
