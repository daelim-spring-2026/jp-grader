package org.example.grader.week04;

import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CH04 실전예제 - 요구사항 분석과 기본 매핑.
 *
 * 학생이 만든 엔티티로 H2 인메모리에 스키마를 실제로 생성한 뒤,
 * INFORMATION_SCHEMA 를 읽어 테이블명과 컬럼명을 확인한다.
 * 코드를 문자열로 뒤지지 않으므로 주석이나 필드 순서가 달라도 영향이 없다.
 *
 * 클래스는 Class.forName 으로 찾는다. 하나가 없어도 컴파일이 깨지지 않고
 * 해당 항목만 실패한다.
 */
class Week04Test {

    /** 수업 자료 패키지를 먼저 찾고, 없으면 대체 패키지를 찾는다. */
    private static final String[] PKGS = {"org.example.ch04.", "org.example.domain."};
    private static final String URL = "jdbc:h2:mem:grade;DB_CLOSE_DELAY=-1";
    /** 뒤 챕터에서 추가되는 엔티티. 없어도 되고, 있으면 같이 등록한다. */
    private static final String[] OPTIONAL = {"Delivery", "Category"};

    /** 테이블명(대문자) -> (컬럼명(대문자) -> 타입) */
    private static final Map<String, Map<String, String>> SCHEMA = new LinkedHashMap<>();
    private static final List<String> MISSING = new ArrayList<>();
    private static String bootError;

    @BeforeAll
    static void 스키마_생성() {
        StandardServiceRegistry registry = null;
        try {
            registry = new StandardServiceRegistryBuilder()
                    .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
                    .applySetting("hibernate.connection.url", URL)
                    .applySetting("hibernate.connection.username", "sa")
                    .applySetting("hibernate.connection.password", "")
                    .applySetting("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
                    .applySetting("hibernate.hbm2ddl.auto", "create")
                    .applySetting("hibernate.physical_naming_strategy",
                            "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                    .applySetting("hibernate.show_sql", "false")
                    .build();

            MetadataSources sources = new MetadataSources(registry);
            for (String n : new String[]{"Member", "Order", "OrderItem", "Item"}) {
                Class<?> found = null;
                for (String pkg : PKGS) {
                    try {
                        found = Class.forName(pkg + n);
                        break;
                    } catch (ClassNotFoundException ignored) {
                        // 다음 후보 패키지를 본다
                    }
                }
                if (found == null) {
                    MISSING.add(n);
                } else {
                    sources.addAnnotatedClass(found);
                }
            }

            // CH06 이후 Order -> Delivery, Item -> Category 를 참조하므로 있으면 같이 등록한다.
            // 등록하지 않으면 "unknown entity" 로 부팅이 실패해 지난 챕터 항목까지 깨진다.
            for (String n : OPTIONAL) {
                for (String pkg : PKGS) {
                    try {
                        sources.addAnnotatedClass(Class.forName(pkg + n));
                        break;
                    } catch (ClassNotFoundException ignored) {
                        // 아직 안 만든 챕터면 없어도 된다
                    }
                }
            }

            SessionFactory sf = sources.buildMetadata().buildSessionFactory();
            sf.close();
            읽기();
        } catch (Throwable t) {
            bootError = t.getClass().getSimpleName() + " : " + t.getMessage();
            if (registry != null) {
                StandardServiceRegistryBuilder.destroy(registry);
            }
        }
    }

    private static void 읽기() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS"
                             + " WHERE TABLE_SCHEMA = 'PUBLIC'")) {
            while (rs.next()) {
                SCHEMA.computeIfAbsent(up(rs.getString(1)), k -> new LinkedHashMap<>())
                        .put(up(rs.getString(2)), up(rs.getString(3)));
            }
        }
    }

    private static String up(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }

    // ---- 공통 확인 ----

    private void 부팅확인() {
        if (bootError != null) {
            fail("엔티티에서 스키마를 만들지 못했습니다. 매핑 오류부터 해결하세요.\n  " + bootError);
        }
    }

    private Map<String, String> 테이블(String table, String className) {
        부팅확인();
        if (MISSING.contains(className)) {
            fail(className + " 클래스를 찾을 수 없습니다."
                    + " 패키지가 org.example.ch04 인지 확인하세요.");
        }
        Map<String, String> cols = SCHEMA.get(up(table));
        if (cols == null) {
            fail(table + " 테이블이 만들어지지 않았습니다. 만들어진 테이블 : " + SCHEMA.keySet());
        }
        return cols;
    }

    private void 컬럼(Map<String, String> cols, String table, String... expected) {
        List<String> 없음 = new ArrayList<>();
        for (String e : expected) {
            if (!cols.containsKey(up(e))) {
                없음.add(e);
            }
        }
        assertTrue(없음.isEmpty(),
                () -> table + " 테이블에 없는 컬럼 " + 없음 + "\n  실제 컬럼 : " + cols.keySet());
    }

    // ---- 채점 항목 ----

    @Test
    @DisplayName("[CH04] Member 는 USERS 테이블로 매핑되고 주소 컬럼을 가진다")
    void member() {
        Map<String, String> cols = 테이블("USERS", "Member");
        컬럼(cols, "USERS", "MEMBER_ID", "NAME", "CITY", "STREET", "ZIPCODE");
    }

    @Test
    @DisplayName("[CH04] Order 는 ORDERS 테이블로 매핑되고 회원 외래 키를 가진다")
    void order() {
        Map<String, String> cols = 테이블("ORDERS", "Order");
        컬럼(cols, "ORDERS", "ORDER_ID", "MEMBER_ID", "ORDER_DATE", "STATUS");
    }

    @Test
    @DisplayName("[CH04] OrderItem 은 ORDER_ITEM 테이블로 매핑된다")
    void orderItem() {
        Map<String, String> cols = 테이블("ORDER_ITEM", "OrderItem");
        컬럼(cols, "ORDER_ITEM", "ORDER_ITEM_ID", "ORDER_ID", "ITEM_ID", "ORDER_PRICE", "COUNT");
    }

    @Test
    @DisplayName("[CH04] Item 은 ITEM 테이블로 매핑된다")
    void item() {
        Map<String, String> cols = 테이블("ITEM", "Item");
        컬럼(cols, "ITEM", "ITEM_ID", "NAME", "PRICE", "STOCK");
    }

    @Test
    @DisplayName("[CH04] 주문 상태는 이름 그대로 저장된다 (@Enumerated(STRING))")
    void 주문상태_문자열() {
        Map<String, String> cols = 테이블("ORDERS", "Order");
        String type = cols.get("STATUS");
        if (type == null) {
            fail("ORDERS 에 STATUS 컬럼이 없습니다. 실제 컬럼 : " + cols.keySet());
        }
        assertTrue(type.contains("CHAR"),
                () -> "STATUS 가 " + type + " 입니다. @Enumerated 를 빼면 숫자로 저장됩니다."
                        + " @Enumerated(EnumType.STRING) 을 붙이세요.");
    }

    @Test
    @DisplayName("[CH04] 주문 날짜는 날짜/시각 타입으로 저장된다 (@Temporal)")
    void 주문날짜_타입() {
        Map<String, String> cols = 테이블("ORDERS", "Order");
        String type = cols.get("ORDER_DATE");
        if (type == null) {
            fail("ORDERS 에 ORDER_DATE 컬럼이 없습니다. 실제 컬럼 : " + cols.keySet());
        }
        assertTrue(type.contains("TIMESTAMP") || type.contains("DATE"),
                () -> "ORDER_DATE 가 " + type + " 입니다. @Temporal(TemporalType.TIMESTAMP) 을 확인하세요.");
    }
}
