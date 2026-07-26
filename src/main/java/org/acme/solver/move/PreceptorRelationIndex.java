package org.acme.solver.move;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.acme.solver.core.PlanningProblem;

/**
 * preceptor/preceptee 그래프의 안정적인 연결 요소 index입니다.
 * 누락된 외부 ID는 기존 점수 의미를 보존하기 위해 연결하지 않고 해당 직원을 singleton으로 둡니다.
 */
public final class PreceptorRelationIndex {

    private final int[] groupIdByEmployee;
    private final Map<Integer, List<Integer>> employeesByGroupId;

    public PreceptorRelationIndex(PlanningProblem problem) {
        Objects.requireNonNull(problem, "problem");
        int employeeCount = problem.employeeCount();
        int[] parent = new int[employeeCount];
        for (int employeeIndex = 0; employeeIndex < employeeCount; employeeIndex++) {
            parent[employeeIndex] = employeeIndex;
        }
        for (int employeeIndex = 0; employeeIndex < employeeCount; employeeIndex++) {
            String preceptorId = problem.employees().get(employeeIndex).preceptorExternalId();
            Integer preceptorIndex = preceptorId == null
                    ? null
                    : problem.employeeIndexByExternalId().get(preceptorId);
            if (preceptorIndex != null) {
                union(parent, employeeIndex, preceptorIndex);
            }
        }

        Map<Integer, List<Integer>> mutableGroups = new LinkedHashMap<>();
        this.groupIdByEmployee = new int[employeeCount];
        for (int employeeIndex = 0; employeeIndex < employeeCount; employeeIndex++) {
            int root = find(parent, employeeIndex);
            mutableGroups.computeIfAbsent(root, ignored -> new ArrayList<>()).add(employeeIndex);
        }

        Map<Integer, List<Integer>> stableGroups = new LinkedHashMap<>();
        for (List<Integer> group : mutableGroups.values()) {
            group.sort(Integer::compareTo);
            int stableId = group.getFirst();
            List<Integer> immutableGroup = List.copyOf(group);
            stableGroups.put(stableId, immutableGroup);
            for (int employeeIndex : group) {
                groupIdByEmployee[employeeIndex] = stableId;
            }
        }
        this.employeesByGroupId = Collections.unmodifiableMap(stableGroups);
    }

    public int groupId(int employeeIndex) {
        return groupIdByEmployee[employeeIndex];
    }

    public List<Integer> employeesInGroup(int groupId) {
        List<Integer> employees = employeesByGroupId.get(groupId);
        if (employees == null) {
            throw new IllegalArgumentException("존재하지 않는 relation group ID입니다: " + groupId);
        }
        return employees;
    }

    public List<Integer> employeesFor(int employeeIndex) {
        return employeesInGroup(groupId(employeeIndex));
    }

    public Map<Integer, List<Integer>> groups() {
        return employeesByGroupId;
    }

    public int[] groupIdsByEmployee() {
        return Arrays.copyOf(groupIdByEmployee, groupIdByEmployee.length);
    }

    private static int find(int[] parent, int value) {
        int root = value;
        while (parent[root] != root) {
            root = parent[root];
        }
        while (parent[value] != value) {
            int next = parent[value];
            parent[value] = root;
            value = next;
        }
        return root;
    }

    private static void union(int[] parent, int left, int right) {
        int leftRoot = find(parent, left);
        int rightRoot = find(parent, right);
        if (leftRoot == rightRoot) {
            return;
        }
        int smaller = Math.min(leftRoot, rightRoot);
        int larger = Math.max(leftRoot, rightRoot);
        parent[larger] = smaller;
    }
}
