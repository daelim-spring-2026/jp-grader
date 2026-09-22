# jp-grader — JavaProject2 채점 테스트

학생 저장소(`jp-학번`)가 push 될 때마다 이 저장소를 통째로 내려받아
`weekNN/` 안의 테스트를 학생 프로젝트에 얹어서 돌린다.

**학생 저장소에는 이 테스트가 남지 않는다.** 여기만 고치면 전원에게 즉시 반영된다.

## 폴더 규칙

- `weekNN/` 의 `NN` 은 **챕터 번호**다. `week04` = CH04.
  요약 스크립트가 테스트의 `classname` 에서 `week(\d+)` 를 뽑아 챕터를 판단한다.
- 테스트의 패키지는 `org.example.grader.weekNN` 이어야 한다.
  워크플로가 `src/test/java/org/example/grader/weekNN/` 로 복사하기 때문이다.

## 채점 방식

코드를 문자열로 뒤지지 않는다. 학생 엔티티로 **H2 인메모리에 스키마를 실제로 만든 뒤**
`INFORMATION_SCHEMA` 를 읽어 테이블명과 컬럼명을 확인한다.
주석, 필드 순서, 게터 유무가 달라도 결과가 흔들리지 않는다.

엔티티 클래스는 `Class.forName` 으로 찾는다. 학생이 클래스 하나를 안 만들었어도
컴파일이 깨지지 않고 **그 항목만** 실패한다.

## 챕터를 추가할 때

1. `weekNN/WeekNNTest.java` 를 만든다 (패키지 `org.example.grader.weekNN`)
2. push 한다
3. 끝. 학생 쪽 조치는 없다.

지난 챕터 테스트도 계속 돌아가므로 회귀 검증이 된다.
단, 뒤 챕터에서 매핑이 바뀌어 앞 챕터 테스트가 깨지는 경우는 미리 확인할 것.
예를 들어 CH05 에서 외래 키 필드를 `@ManyToOne` 으로 바꿔도
`@JoinColumn(name = "...")` 이름이 같으면 컬럼명이 그대로라 CH04 테스트는 계속 통과한다.

## 로컬에서 시험해 보기

```
mvn -B -Dtest='org/example/grader/**/*Test.java' -DfailIfNoSpecifiedTests=false test
```
