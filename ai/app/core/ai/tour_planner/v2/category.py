"""Tour Planner v2 카테고리 그룹 상수.

후보 그룹 분류·균형 cap 계산은 Spring 측이 담당(후보 dict에 `_group` 주입).
Python 은 fallback 그룹 라벨만 참조한다.
"""


# 미분류 fallback 그룹 — graph_orchestrator 가 참조.
GROUP_OTHER = "other"
