package org.acme.solver.alns;

/** 요청 제거 수와 relation 확장 뒤 허용되는 실제 제거 수 상한입니다. */
public record DestroySize(int requestedRemovalCount, int actualRemovalLimit) {

    public DestroySize {
        if (requestedRemovalCount < 0 || actualRemovalLimit < 0) {
            throw new IllegalArgumentException("destroy size는 음수일 수 없습니다.");
        }
        if (requestedRemovalCount > actualRemovalLimit) {
            throw new IllegalArgumentException("요청 제거 수는 실제 제거 상한을 넘을 수 없습니다.");
        }
    }
}
