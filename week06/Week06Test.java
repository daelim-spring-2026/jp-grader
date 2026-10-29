package org.example.grader.week06;

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
import java.sql.SQLException;
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
 * CH06 실전예제 - 다양한 연관관계 매핑.
 *
 * CH05 에 더해지는 것
 *  - 주문 <-> 배송 : 일대일 양방향. 외래 키 ORDERS.DELIVERY_ID, 주인은 Order
 *  - 카테고리 <-> 상품 : 다대다 양방향. 연결 테이블 CATEGORY_ITEM, 주인은 Category
 *  - 카테고리 계층 : CATEGORY.PARENT_ID 로 자기 자신을 참조 (parent / child)
 *
 * Week05 와 같은 방식이다. 외래 키는 INFORMATION_SCHEMA 로 확인하고,
 * 데이터는 SQL 로 직접 넣은 뒤 JPA 로 읽어 객체 그래프를 따라간다.
 * 필드는 이름이 아니라 타입으로 찾으므로 delivery / categories 같은 이름이 달라도 된다.
 */
class Week06Test {

    private static final String[] PKGS = {"org.example.ch04.", "org.example.domain."};
    private static final String URL = "jdbc:h2:mem:grade06;DB_CLOSE_DELAY=-1";

    private static Class<?> MEMBER, ORDER, ORDER_ITEM, ITEM, DELIVERY, CATEGORY;
    private static SessionFactory sf;
    private static String bootError;

    private static final long ORDER_ID = 9201L, DELIVERY_ID = 9501L, ITEM_ID = 9101L;
    private static final long PARENT_ID = 9601L, CHILD_ID = 9602L, CATEGORY_ID = 9611L;

    @BeforeAll
    static void 준비() {
        StandardServiceRegistry registry = null;
        try {
            MEMBER = find("Member");
            ORDER = find("Order");
            ORDER_ITEM = find("OrderItem");
            ITEM = find("Item");
            DELIVERY = find("Delivery");
            CATEGORY = find("Category");

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
            // CH07 이후 Item 을 상속한 Album / Book / Movie 가 있으면 같이 등록한다.
            for (Class<?> c : new Class<?>[]{MEMBER, ORDER, ORDER_ITEM, ITEM, DELIVERY, CATEGORY,
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

    private void 부팅확인() {
        String[] names = {"Member", "Order", "OrderItem", "Item", "Delivery", "Category"};
        Class<?>[] classes = {MEMBER, ORDER, ORDER_ITEM, ITEM, DELIVERY, CATEGORY};
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            if (classes[i] == null) {
                missing.add(names[i]);
            }
        }
        if (!missing.isEmpty()) {
            fail("엔티티 클래스를 찾지 못했습니다 : " + missing
                    + "\n  CH06 실전예제는 Delivery(배송) · Category(카테고리) 를 추가합니다."
                    + "\n  패키지가 org.example.ch04 인지 확인하세요.");
        }
        if (bootError != null) {
            String hint = "";
            if (bootError.contains("mappedBy")) {
                hint = "\n  mappedBy 에 적은 이름이 반대쪽 필드 이름과 다릅니다. 예 : @OneToOne(mappedBy = \"delivery\") 는"
                        + " Order 의 필드 이름이 delivery 여야 합니다.";
            } else if (bootError.contains("unknown entity")) {
                hint = "\n  참조하는 클래스에 @Entity 가 없거나 다른 패키지에 있습니다.";
            } else if (bootError.contains("Repeated column")) {
                hint = "\n  같은 컬럼을 두 필드가 매핑합니다. Long 외래 키 필드(deliveryId 등)를 지우고 참조 필드만 남기세요.";
            }
            fail("엔티티에서 스키마를 만들지 못했습니다." + hint + "\n  " + bootError);
        }
    }

    private static String up(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }

    private static Connection conn() throws SQLException {
        return DriverManager.getConnection(URL, "sa", "");
    }

    private List<String[]> 외래키목록() throws Exception {
        List<String[]> out = new ArrayList<>();
        try (Connection c = conn();
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
        try (Connection c = conn();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'")) {
            while (rs.next()) {
                out.add(up(rs.getString(1)));
            }
        }
        return out;
    }

    /** 컬럼명 -> 데이터 타입 */
    private Map<String, String> 컬럼목록(String table) throws Exception {
        try (Connection c = conn()) {
            return 컬럼목록(c, table);
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

    private String 외래키설명(List<String[]> fks) {
        List<String> have = new ArrayList<>();
        for (String[] fk : fks) {
            have.add(fk[0] + "." + fk[1] + " -> " + fk[2]);
        }
        return have.isEmpty() ? "(없음)" : have.toString();
    }

    private void 외래키확인(String table, String column, String target, String how) throws Exception {
        부팅확인();
        List<String[]> fks = 외래키목록();
        for (String[] fk : fks) {
            if (fk[0].equals(table) && fk[1].equals(column)) {
                assertEquals(target, fk[2],
                        () -> table + "." + column + " 외래 키가 " + fk[2] + " 를 가리킵니다. " + target + " 여야 합니다.");
                return;
            }
        }
        fail(table + "." + column + " 에 외래 키가 없습니다."
                + "\n  " + how
                + "\n  @JoinColumn 을 생략하면 컬럼 이름이 달라집니다."
                + "\n  지금 있는 외래 키 : " + 외래키설명(fks));
    }

    /**
     * 한 행을 넣는다. 값을 주지 않은 NOT NULL 컬럼(int price 같은 기본 타입 필드)은
     * 타입에 맞는 기본값으로 채운다. 학생마다 상품 컬럼이 조금씩 달라도 넣을 수 있게 하기 위함이다.
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

    private interface SqlWork {
        void run(Connection c, Statement st) throws Exception;
    }

    private void 데이터(String what, SqlWork work) {
        try (Connection c = conn(); Statement st = c.createStatement()) {
            work.run(c, st);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage().split("\n")[0];
            fail("채점용 " + what + " 데이터를 넣지 못했습니다. 테이블이나 컬럼 이름이 실전예제와 다릅니다."
                    + "\n  위의 외래 키 · 연결 테이블 항목 안내를 먼저 해결하면 이 항목도 같이 풀립니다."
                    + "\n  원인 : " + msg);
        }
    }

    /** 배송 1 + 그 배송을 가진 주문 1. 외래 키는 ORDERS.DELIVERY_ID */
    private void 배송데이터() {
        데이터("주문/배송", (c, st) -> {
            st.executeUpdate("DELETE FROM ORDERS WHERE ORDER_ID = " + ORDER_ID);
            st.executeUpdate("DELETE FROM DELIVERY WHERE DELIVERY_ID = " + DELIVERY_ID);
            행넣기(c, "DELIVERY", 값("DELIVERY_ID", DELIVERY_ID));
            행넣기(c, "ORDERS", 값("ORDER_ID", ORDER_ID, "DELIVERY_ID", DELIVERY_ID));
        });
    }

    /** 부모 카테고리 1 + 자식 카테고리 1. 외래 키는 CATEGORY.PARENT_ID */
    private void 계층데이터() {
        데이터("카테고리 계층", (c, st) -> {
            st.executeUpdate("DELETE FROM CATEGORY WHERE CATEGORY_ID = " + CHILD_ID);
            st.executeUpdate("DELETE FROM CATEGORY WHERE CATEGORY_ID = " + PARENT_ID);
            행넣기(c, "CATEGORY", 값("CATEGORY_ID", PARENT_ID, "NAME", "부모"));
            행넣기(c, "CATEGORY", 값("CATEGORY_ID", CHILD_ID, "NAME", "자식", "PARENT_ID", PARENT_ID));
        });
    }

    /** 카테고리 1 · 상품 1 을 CATEGORY_ITEM 으로 연결 */
    private void 다대다데이터() {
        데이터("카테고리/상품", (c, st) -> {
            st.executeUpdate("DELETE FROM CATEGORY_ITEM WHERE CATEGORY_ID = " + CATEGORY_ID);
            st.executeUpdate("DELETE FROM CATEGORY WHERE CATEGORY_ID = " + CATEGORY_ID);
            상품삭제(c, st, ITEM_ID);
            행넣기(c, "CATEGORY", 값("CATEGORY_ID", CATEGORY_ID, "NAME", "채점카테고리"));
            상품넣기(c, ITEM_ID, 값("NAME", "채점상품"));
            행넣기(c, "CATEGORY_ITEM", 값("CATEGORY_ID", CATEGORY_ID, "ITEM_ID", ITEM_ID));
        });
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

    private long 아이디(Object entity) {
        Object id = sf.getPersistenceUnitUtil().getIdentifier(entity);
        return ((Number) id).longValue();
    }

    private int 개수(Field f, Object owner) throws Exception {
        Collection<?> c = (Collection<?>) f.get(owner);
        return c == null ? -1 : c.size();
    }

    /** CATEGORY 와 ITEM 을 둘 다 가리키는 테이블 (= 연결 테이블) 목록 */
    private Set<String> 연결테이블들() throws Exception {
        Map<String, Set<String>> targets = new LinkedHashMap<>();
        for (String[] fk : 외래키목록()) {
            targets.computeIfAbsent(fk[0], k -> new LinkedHashSet<>()).add(fk[2]);
        }
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> e : targets.entrySet()) {
            if (e.getValue().contains("CATEGORY") && e.getValue().contains("ITEM")) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    private String 연결테이블힌트() throws Exception {
        Set<String> joins = 연결테이블들();
        joins.remove("CATEGORY_ITEM");
        return joins.isEmpty() ? ""
                : "\n  CATEGORY_ITEM 말고 연결 테이블이 더 있습니다 : " + joins
                + "\n  양쪽 @ManyToMany 가 모두 주인이면 연결 테이블이 두 개 생깁니다."
                + " 주인이 아닌 쪽(Item)에 @ManyToMany(mappedBy = \"items\") 를 쓰세요.";
    }

    private String 추가테이블힌트() throws Exception {
        List<String> extra = new ArrayList<>();
        Set<String> known = Set.of("USERS", "ORDERS", "ORDER_ITEM", "ITEM", "DELIVERY", "CATEGORY", "CATEGORY_ITEM");
        Set<String> children = 상품자식테이블();
        for (String t : 테이블목록()) {
            if (!known.contains(t) && !children.contains(t) && !t.startsWith("HIBERNATE")) {
                extra.add(t);
            }
        }
        return extra.isEmpty() ? ""
                : "\n  예상하지 못한 테이블이 있습니다 : " + extra
                + "\n  @OneToMany 에 mappedBy 가 빠지면 이런 연결 테이블이 생기고, 외래 키 컬럼을 읽지 않습니다.";
    }

    // ---- 채점 항목 : 주문 - 배송 (일대일) ----

    @Test
    @DisplayName("[CH06] ORDERS.DELIVERY_ID 가 DELIVERY 를 가리키는 외래 키다 (주문-배송 일대일)")
    void 주문_배송_외래키() throws Exception {
        외래키확인("ORDERS", "DELIVERY_ID", "DELIVERY",
                "Order 에 @OneToOne @JoinColumn(name = \"DELIVERY_ID\") private Delivery delivery 를 추가하세요."
                        + "\n  외래 키는 주문(ORDERS) 쪽에 둡니다. 연관관계의 주인은 Order 입니다.");
    }

    @Test
    @DisplayName("[CH06] 주문과 배송을 서로 따라갈 수 있다 (일대일 양방향, mappedBy)")
    void 주문_배송_양방향() throws Exception {
        부팅확인();
        Field toDelivery = 타입필드(ORDER, DELIVERY);
        Field toOrder = 타입필드(DELIVERY, ORDER);
        if (toDelivery == null) {
            fail("Order 에 Delivery 타입 필드가 없습니다. @OneToOne @JoinColumn(name = \"DELIVERY_ID\") private Delivery delivery 를 추가하세요.");
        }
        if (toOrder == null) {
            fail("Delivery 에 Order 타입 필드가 없습니다. @OneToOne(mappedBy = \"delivery\") private Order order 를 추가하세요.");
        }
        배송데이터();
        EntityManager em = sf.createEntityManager();
        try {
            Object order = em.find(ORDER, ORDER_ID);
            Object d = toDelivery.get(order);
            assertTrue(d != null, "주문의 배송이 null 입니다. Order.delivery 에 @JoinColumn(name = \"DELIVERY_ID\") 를 확인하세요.");
            assertEquals(DELIVERY_ID, 아이디(d), "주문이 엉뚱한 배송을 가리킵니다.");
            em.clear();

            Object delivery = em.find(DELIVERY, DELIVERY_ID);
            Object o = toOrder.get(delivery);
            String hint = "";
            for (String col : 컬럼목록("DELIVERY").keySet()) {
                if (col.contains("ORDER")) {
                    hint = "\n  DELIVERY 테이블에 " + col + " 컬럼이 있습니다. 양쪽이 모두 주인이 되어 외래 키가 두 개 생겼습니다.";
                }
            }
            final String h = hint;
            assertTrue(o != null, () -> "배송에서 주문으로 따라가면 null 입니다."
                    + "\n  Delivery.order 에 @OneToOne(mappedBy = \"delivery\") 를 쓰세요. (주인이 아닌 쪽, 읽기 전용)" + h);
            assertEquals(ORDER_ID, 아이디(o), "배송이 엉뚱한 주문을 가리킵니다.");
        } finally {
            em.close();
        }
    }

    @Test
    @DisplayName("[CH06] 배송 상태는 이름 그대로 저장된다 (@Enumerated(STRING))")
    void 배송상태_문자열() throws Exception {
        부팅확인();
        Map<String, String> cols = 컬럼목록("DELIVERY");
        String type = cols.get("STATUS");
        if (type == null) {
            fail("DELIVERY 에 STATUS 컬럼이 없습니다. DeliveryStatus status 필드를 추가하세요 (READY, COMPLETE)."
                    + "\n  실제 컬럼 : " + cols.keySet());
        }
        assertTrue(type.contains("CHAR"),
                () -> "DELIVERY.STATUS 가 " + type + " 입니다. @Enumerated 를 빼면 숫자로 저장됩니다."
                        + " @Enumerated(EnumType.STRING) 을 붙이세요.");
    }

    // ---- 채점 항목 : 카테고리 계층 (자기 참조) ----

    @Test
    @DisplayName("[CH06] CATEGORY.PARENT_ID 가 CATEGORY 를 가리키는 외래 키다 (자기 참조)")
    void 카테고리_부모_외래키() throws Exception {
        외래키확인("CATEGORY", "PARENT_ID", "CATEGORY",
                "Category 에 @ManyToOne @JoinColumn(name = \"PARENT_ID\") private Category parent 를 추가하세요.");
    }

    @Test
    @DisplayName("[CH06] 부모 카테고리에서 자식 목록으로 따라갈 수 있다 (parent / child)")
    void 카테고리_자식목록() throws Exception {
        부팅확인();
        Field parent = 타입필드(CATEGORY, CATEGORY);
        Field child = 컬렉션필드(CATEGORY, CATEGORY);
        if (parent == null) {
            fail("Category 에 부모(Category parent) 필드가 없습니다. @ManyToOne @JoinColumn(name = \"PARENT_ID\") 를 붙이세요.");
        }
        if (child == null) {
            fail("Category 에 자식 목록(List<Category> child) 필드가 없습니다. @OneToMany(mappedBy = \"parent\") 를 붙이세요.");
        }
        계층데이터();
        EntityManager em = sf.createEntityManager();
        try {
            Object c = em.find(CATEGORY, CHILD_ID);
            Object p = parent.get(c);
            assertTrue(p != null, "자식 카테고리의 parent 가 null 입니다. @JoinColumn(name = \"PARENT_ID\") 를 확인하세요.");
            assertEquals(PARENT_ID, 아이디(p), "자식 카테고리가 엉뚱한 부모를 가리킵니다.");
            em.clear();

            Object root = em.find(CATEGORY, PARENT_ID);
            int n = 개수(child, root);
            String hint = 추가테이블힌트();
            assertEquals(1, n, () -> "부모 카테고리의 자식 목록이 " + n + " 개입니다. DB 에는 자식이 1개 있습니다."
                    + "\n  @OneToMany(mappedBy = \"parent\") 를 확인하세요." + hint);
        } finally {
            em.close();
        }
    }

    // ---- 채점 항목 : 카테고리 - 상품 (다대다) ----

    @Test
    @DisplayName("[CH06] 연결 테이블 CATEGORY_ITEM 이 CATEGORY_ID · ITEM_ID 외래 키를 가진다 (다대다)")
    void 연결테이블() throws Exception {
        부팅확인();
        List<String[]> fks = 외래키목록();
        boolean toCategory = false, toItem = false;
        for (String[] fk : fks) {
            if (fk[0].equals("CATEGORY_ITEM") && fk[1].equals("CATEGORY_ID") && fk[2].equals("CATEGORY")) {
                toCategory = true;
            }
            if (fk[0].equals("CATEGORY_ITEM") && fk[1].equals("ITEM_ID") && fk[2].equals("ITEM")) {
                toItem = true;
            }
        }
        if (toCategory && toItem) {
            return;
        }
        Set<String> joins = 연결테이블들();
        fail("CATEGORY_ITEM 연결 테이블에 CATEGORY_ID -> CATEGORY, ITEM_ID -> ITEM 외래 키가 있어야 합니다."
                + "\n  Category.items 에 다음을 붙이세요 :"
                + "\n  @ManyToMany @JoinTable(name = \"CATEGORY_ITEM\","
                + " joinColumns = @JoinColumn(name = \"CATEGORY_ID\"),"
                + " inverseJoinColumns = @JoinColumn(name = \"ITEM_ID\"))"
                + "\n  @JoinTable 을 생략하면 이름이 CATEGORY_ITEMS 처럼 달라집니다."
                + "\n  지금 있는 연결 테이블 : " + (joins.isEmpty() ? "(없음)" : joins)
                + "\n  지금 있는 외래 키 : " + 외래키설명(fks));
    }

    @Test
    @DisplayName("[CH06] 카테고리에서 상품 목록으로 따라갈 수 있다 (다대다, 주인 Category)")
    void 카테고리에서_상품목록() throws Exception {
        부팅확인();
        Field items = 컬렉션필드(CATEGORY, ITEM);
        if (items == null) {
            fail("Category 에 상품 목록(List<Item> items) 필드가 없습니다."
                    + "\n  @ManyToMany @JoinTable(name = \"CATEGORY_ITEM\", ...) 을 붙여 추가하세요.");
        }
        다대다데이터();
        EntityManager em = sf.createEntityManager();
        try {
            Object category = em.find(CATEGORY, CATEGORY_ID);
            int n = 개수(items, category);
            String hint = 연결테이블힌트();
            assertEquals(1, n, () -> "카테고리의 상품 목록이 " + n + " 개입니다. CATEGORY_ITEM 에는 이 카테고리의 상품이 1개 있습니다."
                    + "\n  Category.items 가 연관관계의 주인입니다. mappedBy 없이 @JoinTable 을 써야 합니다." + hint);
        } finally {
            em.close();
        }
    }

    @Test
    @DisplayName("[CH06] 상품에서 카테고리 목록으로 따라갈 수 있다 (다대다 양방향, mappedBy)")
    void 상품에서_카테고리목록() throws Exception {
        부팅확인();
        Field categories = 컬렉션필드(ITEM, CATEGORY);
        if (categories == null) {
            fail("Item 에 카테고리 목록(List<Category> categories) 필드가 없습니다."
                    + "\n  @ManyToMany(mappedBy = \"items\") 를 붙여 추가하세요.");
        }
        다대다데이터();
        EntityManager em = sf.createEntityManager();
        try {
            Object item = em.find(ITEM, ITEM_ID);
            int n = 개수(categories, item);
            String hint = 연결테이블힌트();
            assertEquals(1, n, () -> "상품의 카테고리 목록이 " + n + " 개입니다. CATEGORY_ITEM 에는 이 상품의 카테고리가 1개 있습니다."
                    + "\n  Item.categories 에 @ManyToMany(mappedBy = \"items\") 를 쓰세요." + hint);
        } finally {
            em.close();
        }
    }
}
