package org.example.grader.week05;

import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.persistence.EntityManager;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CH05 실전예제 - 연관관계 매핑.
 *
 * 1) 외래 키 제약조건이 실제로 만들어졌는지 INFORMATION_SCHEMA 로 확인한다.
 * 2) 데이터는 SQL 로 직접 넣고, JPA 로 다시 읽어 객체 그래프를 따라간다.
 *
 * 필드 이름에 기대지 않는다. Order 안에서 "Member 타입 필드", Member 안에서
 * "Order 를 담는 컬렉션 필드" 를 타입으로 찾으므로 member / orders 같은 이름이 달라도 된다.
 */
class Week05Test {

    private static final String[] PKGS = {"org.example.ch04.", "org.example.domain."};
    private static final String URL = "jdbc:h2:mem:grade05;DB_CLOSE_DELAY=-1";

    private static Class<?> MEMBER, ORDER, ORDER_ITEM, ITEM;
    private static SessionFactory sf;
    private static String bootError;

    private static final long MEMBER_ID = 9001L, ITEM_ID = 9101L, ORDER_ID = 9201L, ORDER_ITEM_ID = 9301L;

    @BeforeAll
    static void 준비() {
        StandardServiceRegistry registry = null;
        try {
            MEMBER = find("Member");
            ORDER = find("Order");
            ORDER_ITEM = find("OrderItem");
            ITEM = find("Item");

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
            // CH06 이후 Order -> Delivery, Item -> Category 를 참조하므로 있으면 같이 등록한다.
            // CH07 이후 Item 을 상속한 Album / Book / Movie 도 같이 등록한다.
            // 등록하지 않으면 "unknown entity" 로 부팅이 실패해 지난 챕터 항목까지 깨진다.
            for (Class<?> c : new Class<?>[]{MEMBER, ORDER, ORDER_ITEM, ITEM, find("Delivery"), find("Category"),
                    find("Album"), find("Book"), find("Movie")}) {
                if (c != null) {
                    sources.addAnnotatedClass(c);
                }
            }
            sf = sources.buildMetadata().buildSessionFactory();
        } catch (Throwable t) {
            Throwable r = t;
            while (r.getCause() != null && r.getCause() != r) {
                r = r.getCause();
            }
            bootError = r.getClass().getSimpleName() + " : " + r.getMessage();
            if (registry != null) {
                StandardServiceRegistryBuilder.destroy(registry);
            }
        }
    }

    @AfterAll
    static void 정리() {
        if (sf != null) {
            sf.close();
        }
    }

    private static Class<?> find(String simple) {
        for (String p : PKGS) {
            try {
                return Class.forName(p + simple);
            } catch (ClassNotFoundException ignored) {
                // 다음 후보
            }
        }
        return null;
    }

    // ---- 공통 ----

    private void 부팅확인(Class<?>... need) {
        for (Class<?> c : need) {
            if (c == null) {
                fail("엔티티 클래스를 찾지 못했습니다. 패키지가 org.example.ch04 인지 확인하세요.");
            }
        }
        if (bootError != null) {
            String hint = bootError.contains("Repeated column")
                    ? "\n  같은 컬럼을 두 필드가 매핑합니다. CH04 의 Long 외래 키 필드(memberId 등)를 지우고 참조 필드만 남기세요."
                    : "";
            fail("엔티티에서 스키마를 만들지 못했습니다." + hint + "\n  " + bootError);
        }
    }

    private List<String[]> 외래키목록() throws Exception {
        List<String[]> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT fk.TABLE_NAME, kcu.COLUMN_NAME, pk.TABLE_NAME"
                             + " FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS rc"
                             + " JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS fk ON fk.CONSTRAINT_NAME = rc.CONSTRAINT_NAME"
                             + " JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu ON kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME"
                             + " JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS pk ON pk.CONSTRAINT_NAME = rc.UNIQUE_CONSTRAINT_NAME")) {
            while (rs.next()) {
                out.add(new String[]{up(rs.getString(1)), up(rs.getString(2)), up(rs.getString(3))});
            }
        }
        return out;
    }

    private Set<String> 테이블목록() throws Exception {
        Set<String> out = new LinkedHashSet<>();
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'")) {
            while (rs.next()) {
                out.add(up(rs.getString(1)));
            }
        }
        return out;
    }

    private static String up(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }

    private void 외래키확인(String table, String column, String target, String field) throws Exception {
        부팅확인(MEMBER, ORDER, ORDER_ITEM, ITEM);
        List<String[]> fks = 외래키목록();
        for (String[] fk : fks) {
            if (fk[0].equals(table) && fk[1].equals(column)) {
                assertEquals(target, fk[2],
                        () -> table + "." + column + " 외래 키가 " + fk[2] + " 를 가리킵니다. " + target + " 여야 합니다.");
                return;
            }
        }
        List<String> have = new ArrayList<>();
        for (String[] fk : fks) {
            have.add(fk[0] + "." + fk[1] + " -> " + fk[2]);
        }
        fail(table + "." + column + " 에 외래 키가 없습니다."
                + "\n  " + field + " 를 참조로 바꾸고 @ManyToOne 과 @JoinColumn(name = \"" + column + "\") 을 붙이세요."
                + "\n  @JoinColumn 을 생략하면 컬럼 이름이 달라집니다 (예 : MEMBER_MEMBER_ID)."
                + "\n  지금 있는 외래 키 : " + (have.isEmpty() ? "(없음)" : have));
    }

    /** SQL 로 회원 1 · 상품 1 · 주문 1 · 주문상품 1 을 넣는다. */
    private void 데이터넣기() throws Exception {
        try {
            데이터넣기_실행();
        } catch (java.sql.SQLException e) {
            fail("채점용 데이터를 넣지 못했습니다. 테이블이나 컬럼 이름이 CH04 와 다릅니다."
                    + "\n  외래 키 컬럼은 MEMBER_ID · ORDER_ID · ITEM_ID 여야 합니다."
                    + "\n  위의 외래 키 항목 안내를 먼저 해결하면 이 항목도 같이 풀립니다."
                    + "\n  원인 : " + e.getMessage().split("\n")[0]);
        }
    }

    private void 데이터넣기_실행() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM ORDER_ITEM WHERE ORDER_ITEM_ID = " + ORDER_ITEM_ID);
            st.executeUpdate("DELETE FROM ORDERS WHERE ORDER_ID = " + ORDER_ID);
            상품삭제(c, st, ITEM_ID);
            st.executeUpdate("DELETE FROM USERS WHERE MEMBER_ID = " + MEMBER_ID);
            st.executeUpdate("INSERT INTO USERS (MEMBER_ID, NAME) VALUES (" + MEMBER_ID + ", '채점회원')");
            상품넣기(c, ITEM_ID, 값("NAME", "채점상품", "PRICE", 1000, "STOCK", 10));
            st.executeUpdate("INSERT INTO ORDERS (ORDER_ID, MEMBER_ID, STATUS) VALUES (" + ORDER_ID + ", " + MEMBER_ID + ", 'ORDER')");
            st.executeUpdate("INSERT INTO ORDER_ITEM (ORDER_ITEM_ID, ORDER_ID, ITEM_ID, ORDER_PRICE, COUNT) VALUES ("
                    + ORDER_ITEM_ID + ", " + ORDER_ID + ", " + ITEM_ID + ", 1000, 1)");
        }
    }

    private Map<String, String> 컬럼목록(Connection c, String table) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS"
                        + " WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(up(rs.getString(1)), up(rs.getString(2)));
                }
            }
        }
        return out;
    }

    /**
     * 한 행을 넣는다. 값을 주지 않은 NOT NULL 컬럼(int price 같은 기본 타입 필드)은
     * 타입에 맞는 기본값으로 채운다. (Week06 과 같은 방식)
     */
    private void 행넣기(Connection c, String table, Map<String, Object> values) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>(values);
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS"
                        + " WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ? AND IS_NULLABLE = 'NO'"
                        + " AND COLUMN_DEFAULT IS NULL")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String col = up(rs.getString(1));
                    String type = up(rs.getString(2));
                    if (row.containsKey(col)) {
                        continue;
                    }
                    if (type.contains("CHAR")) {
                        row.put(col, "x");
                    } else if (type.contains("BOOL")) {
                        row.put(col, false);
                    } else if (type.contains("TIMESTAMP") || type.contains("DATE")) {
                        row.put(col, new java.sql.Timestamp(0));
                    } else {
                        row.put(col, 0);
                    }
                }
            }
        }
        StringBuilder cols = new StringBuilder();
        StringBuilder qs = new StringBuilder();
        for (String k : row.keySet()) {
            cols.append(cols.length() == 0 ? "" : ", ").append(k);
            qs.append(qs.length() == 0 ? "" : ", ").append("?");
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO " + table + " (" + cols + ") VALUES (" + qs + ")")) {
            int i = 1;
            for (Object v : row.values()) {
                ps.setObject(i++, v);
            }
            ps.executeUpdate();
        }
    }

    private static Map<String, Object> 값(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ---- CH07 상속 대응 ----

    private static final String[] ITEM_CHILDREN = {"Album", "Book", "Movie"};

    /**
     * 상품 행을 넣을 때 쓸 엔티티. Item 이 추상 클래스(CH07 상속)면 자식 중 첫 번째 구체 클래스를 고른다.
     * ITEM 에만 행을 넣으면 구분 컬럼(DTYPE)이 비거나, 추상 클래스를 만들 수 없어 읽기가 실패하기 때문이다.
     */
    private AbstractEntityPersister 상품퍼시스터() {
        List<Class<?>> candidates = new ArrayList<>();
        if (!Modifier.isAbstract(ITEM.getModifiers())) {
            candidates.add(ITEM);
        }
        for (String n : ITEM_CHILDREN) {
            Class<?> c = find(n);
            if (c != null && ITEM.isAssignableFrom(c) && !Modifier.isAbstract(c.getModifiers())) {
                candidates.add(c);
            }
        }
        for (Class<?> c : candidates) {
            try {
                return (AbstractEntityPersister) sf.unwrap(SessionFactoryImplementor.class)
                        .getMetamodel().entityPersister(c);
            } catch (RuntimeException ignored) {
                // 엔티티로 등록되지 않은 클래스
            }
        }
        return null;
    }

    private String 기본키컬럼(Connection c, String table) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT kcu.COLUMN_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc"
                        + " JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu ON kcu.CONSTRAINT_NAME = tc.CONSTRAINT_NAME"
                        + " WHERE tc.TABLE_NAME = ? AND tc.CONSTRAINT_TYPE = 'PRIMARY KEY'")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? up(rs.getString(1)) : "ITEM_ID";
            }
        }
    }

    /** 조인 전략(JOINED)이면 자식 테이블(ALBUM 등) 이름들. 단일 테이블 전략이면 비어 있다. */
    private Set<String> 상품자식테이블() {
        Set<String> out = new LinkedHashSet<>();
        for (String n : ITEM_CHILDREN) {
            Class<?> c = find(n);
            if (c == null || !ITEM.isAssignableFrom(c)) {
                continue;
            }
            try {
                String t = up(((AbstractEntityPersister) sf.unwrap(SessionFactoryImplementor.class)
                        .getMetamodel().entityPersister(c)).getTableName());
                if (!t.equals("ITEM")) {
                    out.add(t);
                }
            } catch (RuntimeException ignored) {
                // 엔티티로 등록되지 않은 클래스
            }
        }
        return out;
    }

    private void 상품삭제(Connection c, Statement st, long id) throws Exception {
        for (String t : 상품자식테이블()) {
            st.executeUpdate("DELETE FROM " + t + " WHERE " + 기본키컬럼(c, t) + " = " + id);
        }
        st.executeUpdate("DELETE FROM ITEM WHERE ITEM_ID = " + id);
    }

    private void 상품넣기(Connection c, long id, Map<String, Object> values) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>(values);
        row.put("ITEM_ID", id);
        AbstractEntityPersister p = 상품퍼시스터();
        if (p != null) {
            String dcol = up(p.getDiscriminatorColumnName());
            Object dval = p.getDiscriminatorSQLValue();
            if (!dcol.isEmpty() && dval != null && 컬럼목록(c, "ITEM").containsKey(dcol)) {
                row.put(dcol, dval.toString().replaceAll("^'|'$", ""));
            }
        }
        행넣기(c, "ITEM", row);
        if (p != null && !up(p.getTableName()).equals("ITEM")) {
            String t = up(p.getTableName());
            행넣기(c, t, 값(기본키컬럼(c, t), id));
        }
    }

    private static Field 타입필드(Class<?> owner, Class<?> type) {
        for (Field f : owner.getDeclaredFields()) {
            if (f.getType().equals(type)) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    private static Field 컬렉션필드(Class<?> owner, Class<?> element) {
        for (Field f : owner.getDeclaredFields()) {
            if (!Collection.class.isAssignableFrom(f.getType())) {
                continue;
            }
            Type g = f.getGenericType();
            if (g instanceof ParameterizedType
                    && ((ParameterizedType) g).getActualTypeArguments()[0].equals(element)) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    private String 연결테이블힌트() throws Exception {
        List<String> extra = new ArrayList<>();
        for (String t : 테이블목록()) {
            if (!Set.of("USERS", "ORDERS", "ORDER_ITEM", "ITEM", "DELIVERY", "CATEGORY", "CATEGORY_ITEM").contains(t)
                    && !상품자식테이블().contains(t) && !t.startsWith("HIBERNATE")) {
                extra.add(t);
            }
        }
        return extra.isEmpty() ? ""
                : "\n  예상하지 못한 테이블이 있습니다 : " + extra
                + "\n  @OneToMany 에 mappedBy 가 빠지면 이런 연결 테이블이 생기고, 외래 키 컬럼을 읽지 않습니다.";
    }

    // ---- 채점 항목 ----

    @Test
    @DisplayName("[CH05] ORDERS.MEMBER_ID 가 USERS 를 가리키는 외래 키다")
    void 주문_회원_외래키() throws Exception {
        외래키확인("ORDERS", "MEMBER_ID", "USERS", "Order 의 memberId");
    }

    @Test
    @DisplayName("[CH05] ORDER_ITEM.ORDER_ID 가 ORDERS 를 가리키는 외래 키다")
    void 주문상품_주문_외래키() throws Exception {
        외래키확인("ORDER_ITEM", "ORDER_ID", "ORDERS", "OrderItem 의 orderId");
    }

    @Test
    @DisplayName("[CH05] ORDER_ITEM.ITEM_ID 가 ITEM 을 가리키는 외래 키다")
    void 주문상품_상품_외래키() throws Exception {
        외래키확인("ORDER_ITEM", "ITEM_ID", "ITEM", "OrderItem 의 itemId");
    }

    @Test
    @DisplayName("[CH05] 주문에서 회원으로 따라갈 수 있다 (다대일)")
    void 주문에서_회원() throws Exception {
        부팅확인(MEMBER, ORDER, ORDER_ITEM, ITEM);
        Field f = 타입필드(ORDER, MEMBER);
        if (f == null) {
            fail("Order 에 Member 타입 필드가 없습니다. Long memberId 를 Member member 로 바꾸세요.");
        }
        데이터넣기();
        EntityManager em = sf.createEntityManager();
        try {
            Object order = em.find(ORDER, ORDER_ID);
            Object member = f.get(order);
            assertTrue(member != null, "주문의 회원이 null 입니다. @ManyToOne 과 @JoinColumn(name = \"MEMBER_ID\") 을 확인하세요.");
            Object id = sf.getPersistenceUnitUtil().getIdentifier(member);
            assertEquals(MEMBER_ID, ((Number) id).longValue(), "주문이 엉뚱한 회원을 가리킵니다.");
        } finally {
            em.close();
        }
    }

    @Test
    @DisplayName("[CH05] 회원에서 주문 목록으로 따라갈 수 있다 (양방향)")
    void 회원에서_주문목록() throws Exception {
        부팅확인(MEMBER, ORDER, ORDER_ITEM, ITEM);
        Field f = 컬렉션필드(MEMBER, ORDER);
        if (f == null) {
            fail("Member 에 주문 목록(List<Order>) 필드가 없습니다."
                    + "\n  @OneToMany(mappedBy = \"member\") List<Order> orders 를 추가하세요.");
        }
        데이터넣기();
        EntityManager em = sf.createEntityManager();
        try {
            Object member = em.find(MEMBER, MEMBER_ID);
            Collection<?> orders = (Collection<?>) f.get(member);
            int n = orders == null ? -1 : orders.size();
            String hint = 연결테이블힌트();
            assertEquals(1, n, () -> "회원의 주문 목록이 " + n + " 개입니다. DB 에는 이 회원의 주문이 1개 있습니다." + hint);
        } finally {
            em.close();
        }
    }

    @Test
    @DisplayName("[CH05] 주문에서 주문상품 목록으로 따라갈 수 있다 (양방향)")
    void 주문에서_주문상품목록() throws Exception {
        부팅확인(MEMBER, ORDER, ORDER_ITEM, ITEM);
        Field f = 컬렉션필드(ORDER, ORDER_ITEM);
        if (f == null) {
            fail("Order 에 주문상품 목록(List<OrderItem>) 필드가 없습니다."
                    + "\n  @OneToMany(mappedBy = \"order\") List<OrderItem> orderItems 를 추가하세요.");
        }
        데이터넣기();
        EntityManager em = sf.createEntityManager();
        try {
            Object order = em.find(ORDER, ORDER_ID);
            Collection<?> items = (Collection<?>) f.get(order);
            int n = items == null ? -1 : items.size();
            String hint = 연결테이블힌트();
            assertEquals(1, n, () -> "주문의 주문상품 목록이 " + n + " 개입니다. DB 에는 이 주문의 주문상품이 1개 있습니다." + hint);
        } finally {
            em.close();
        }
    }
}
