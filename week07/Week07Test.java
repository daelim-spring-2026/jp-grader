package org.example.grader.week07;

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

import javax.persistence.Entity;
import javax.persistence.EntityManager;
import javax.persistence.Inheritance;
import javax.persistence.InheritanceType;
import javax.persistence.MappedSuperclass;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CH07 실전예제 - 고급 매핑.
 *
 * CH06 에 더해지는 것
 *  - 상품의 종류는 음반(Album) · 도서(Book) · 영화(Movie) : Item 을 추상 클래스로 만들고 상속 매핑
 *    상속 전략은 단일 테이블(SINGLE_TABLE) · 조인(JOINED) 둘 다 인정한다.
 *  - 모든 데이터는 등록일과 수정일이 있어야 한다 : @MappedSuperclass BaseEntity 를 상속
 *    컬럼은 CREATED_DATE · LAST_MODIFIED_DATE (필드 createdDate · lastModifiedDate)
 *
 * 스키마는 INFORMATION_SCHEMA 로 확인하고, 다형성 조회는 JPA 로 저장한 뒤 다시 읽어 타입을 본다.
 */
class Week07Test {

    private static final String[] PKGS = {"org.example.ch04.", "org.example.domain."};
    private static final String URL = "jdbc:h2:mem:grade07;DB_CLOSE_DELAY=-1";

    private static final String[] BASE = {"Member", "Order", "OrderItem", "Item", "Delivery", "Category"};
    private static final String[] CHILDREN = {"Album", "Book", "Movie"};
    /** 자식 엔티티 -> 고유 컬럼 */
    private static final Map<String, String[]> CHILD_COLUMNS = new LinkedHashMap<>();

    static {
        CHILD_COLUMNS.put("Album", new String[]{"ARTIST", "ETC"});
        CHILD_COLUMNS.put("Book", new String[]{"AUTHOR", "ISBN"});
        CHILD_COLUMNS.put("Movie", new String[]{"DIRECTOR", "ACTOR"});
    }

    /** 등록일 · 수정일이 있어야 하는 테이블 (엔티티 -> 테이블) */
    private static final Map<String, String> ENTITY_TABLES = new LinkedHashMap<>();

    static {
        ENTITY_TABLES.put("Member", "USERS");
        ENTITY_TABLES.put("Order", "ORDERS");
        ENTITY_TABLES.put("OrderItem", "ORDER_ITEM");
        ENTITY_TABLES.put("Item", "ITEM");
        ENTITY_TABLES.put("Delivery", "DELIVERY");
        ENTITY_TABLES.put("Category", "CATEGORY");
    }

    private static Class<?> ITEM;
    private static SessionFactory sf;
    private static String bootError;

    @BeforeAll
    static void 준비() {
        StandardServiceRegistry registry = null;
        try {
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
            List<String> all = new ArrayList<>(List.of(BASE));
            all.addAll(List.of(CHILDREN));
            for (String n : all) {
                Class<?> c = find(n);
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
        List<String> missing = new ArrayList<>();
        for (String n : BASE) {
            if (find(n) == null) {
                missing.add(n);
            }
        }
        if (!missing.isEmpty()) {
            fail("엔티티 클래스를 찾지 못했습니다 : " + missing + "\n  패키지가 org.example.ch04 인지 확인하세요.");
        }
        if (bootError != null) {
            String hint = "";
            if (bootError.contains("Cannot use identity column key generation")) {
                hint = "\n  TABLE_PER_CLASS 전략에서는 IDENTITY 를 쓸 수 없습니다. 실전예제는 SINGLE_TABLE 이나 JOINED 를 쓰세요.";
            } else if (bootError.contains("discriminator")) {
                hint = "\n  @DiscriminatorValue 값이 자식끼리 겹치지 않는지 확인하세요 (예 : A · B · M).";
            } else if (bootError.contains("share the same JPA entity name")) {
                hint = "\n  CH07 수업 예제의 Item · Album 등이 같은 프로젝트에 남아 있어 이름이 겹칩니다."
                        + " 수업 예제는 다른 이름이나 다른 프로젝트로 옮기세요.";
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

    /** 컬럼명 -> 데이터 타입. 테이블이 없으면 빈 맵 */
    private Map<String, String> 컬럼목록(String table) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
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

    private AbstractEntityPersister 퍼시스터(Class<?> c) {
        try {
            return (AbstractEntityPersister) sf.unwrap(SessionFactoryImplementor.class).getMetamodel().entityPersister(c);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Item 을 상속한 @Entity 자식들. 없거나 상속하지 않으면 실패 메시지 */
    private List<Class<?>> 자식들() {
        List<Class<?>> out = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (String n : CHILDREN) {
            Class<?> c = find(n);
            if (c == null) {
                problems.add(n + " 클래스가 없습니다");
            } else if (c.getSuperclass() != ITEM) {
                problems.add(n + " 이 Item 을 상속하지 않습니다 (public class " + n + " extends Item)");
            } else if (!c.isAnnotationPresent(Entity.class)) {
                problems.add(n + " 에 @Entity 가 없습니다");
            } else {
                out.add(c);
            }
        }
        if (!problems.isEmpty()) {
            fail("상품 종류(음반 · 도서 · 영화)를 Item 의 자식 엔티티로 만들어야 합니다."
                    + "\n  - " + String.join("\n  - ", problems));
        }
        return out;
    }

    // ---- 채점 항목 : 상속 관계 매핑 ----

    @Test
    @DisplayName("[CH07] Item 은 추상 클래스이고 상속 매핑(@Inheritance)을 쓴다")
    void 상품_추상클래스() {
        부팅확인();
        assertTrue(Modifier.isAbstract(ITEM.getModifiers()),
                "Item 이 추상 클래스가 아닙니다. 상품은 항상 음반 · 도서 · 영화 중 하나이므로 public abstract class Item 으로 만드세요.");
        Inheritance inh = ITEM.getAnnotation(Inheritance.class);
        if (inh == null) {
            fail("Item 에 @Inheritance 가 없습니다."
                    + "\n  @Inheritance(strategy = InheritanceType.SINGLE_TABLE) 또는 JOINED 를 붙이세요.");
        }
        assertTrue(inh.strategy() == InheritanceType.SINGLE_TABLE || inh.strategy() == InheritanceType.JOINED,
                () -> "상속 전략이 " + inh.strategy() + " 입니다. 실전예제는 SINGLE_TABLE 이나 JOINED 를 쓰세요."
                        + "\n  TABLE_PER_CLASS 는 일반적으로 추천하지 않는 전략입니다.");
    }

    @Test
    @DisplayName("[CH07] 음반 · 도서 · 영화(Album · Book · Movie)가 Item 을 상속한 엔티티다")
    void 자식_엔티티() {
        부팅확인();
        자식들();
    }

    @Test
    @DisplayName("[CH07] 음반 · 도서 · 영화의 고유 컬럼이 있다 (artist·etc / author·isbn / director·actor)")
    void 자식_컬럼() throws Exception {
        부팅확인();
        List<String> problems = new ArrayList<>();
        for (Class<?> c : 자식들()) {
            AbstractEntityPersister p = 퍼시스터(c);
            String table = p == null ? "" : up(p.getTableName());
            Map<String, String> cols = 컬럼목록(table);
            for (String col : CHILD_COLUMNS.get(c.getSimpleName())) {
                if (!cols.containsKey(col)) {
                    problems.add(c.getSimpleName() + " : " + table + " 에 " + col + " 컬럼이 없습니다");
                }
            }
        }
        if (!problems.isEmpty()) {
            fail("자식 엔티티의 필드를 확인하세요."
                    + "\n  Album : artist, etc / Book : author, isbn / Movie : director, actor"
                    + "\n  - " + String.join("\n  - ", problems));
        }
    }

    @Test
    @DisplayName("[CH07] ITEM 에 구분 컬럼 DTYPE 이 있다 (@DiscriminatorColumn)")
    void 구분컬럼() throws Exception {
        부팅확인();
        Map<String, String> cols = 컬럼목록("ITEM");
        assertTrue(cols.containsKey("DTYPE"),
                () -> "ITEM 테이블에 DTYPE 컬럼이 없습니다."
                        + "\n  Item 에 @DiscriminatorColumn(name = \"DTYPE\") 을 붙이세요."
                        + " 조인 전략은 이 어노테이션을 빼면 구분 컬럼이 만들어지지 않습니다."
                        + "\n  지금 ITEM 컬럼 : " + cols.keySet());
    }

    @Test
    @DisplayName("[CH07] 저장한 상품을 다시 조회하면 음반 · 도서 · 영화 타입으로 구분되어 나온다")
    void 다형성_조회() throws Exception {
        부팅확인();
        List<Class<?>> children = 자식들();
        List<Object> ids = new ArrayList<>();
        EntityManager em = sf.createEntityManager();
        try {
            em.getTransaction().begin();
            for (Class<?> c : children) {
                Constructor<?> ctor;
                try {
                    ctor = c.getDeclaredConstructor();
                } catch (NoSuchMethodException e) {
                    fail(c.getSimpleName() + " 에 기본 생성자가 없습니다. JPA 엔티티는 기본 생성자가 있어야 합니다.");
                    return;
                }
                ctor.setAccessible(true);
                Object o = ctor.newInstance();
                em.persist(o);
                ids.add(sf.getPersistenceUnitUtil().getIdentifier(o));
            }
            em.getTransaction().commit();
        } catch (Exception e) {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            Throwable r = e;
            while (r.getCause() != null && r.getCause() != r) {
                r = r.getCause();
            }
            fail("음반 · 도서 · 영화를 저장하지 못했습니다. Item 의 id 에 @GeneratedValue 가 있는지 확인하세요."
                    + "\n  원인 : " + r.getMessage());
        } finally {
            em.close();
        }

        em = sf.createEntityManager();
        try {
            for (int i = 0; i < children.size(); i++) {
                Class<?> expected = children.get(i);
                Object found = em.find(ITEM, ids.get(i));
                assertTrue(found != null, () -> "저장한 " + expected.getSimpleName() + " 를 em.find(Item.class, id) 로 찾지 못했습니다.");
                assertTrue(expected.isInstance(found),
                        () -> expected.getSimpleName() + " 로 저장했는데 " + found.getClass().getSimpleName() + " 로 조회됩니다."
                                + "\n  자식마다 @DiscriminatorValue 를 다르게 주었는지 확인하세요.");
            }
        } finally {
            em.close();
        }
    }

    // ---- 채점 항목 : @MappedSuperclass ----

    @Test
    @DisplayName("[CH07] BaseEntity 는 @MappedSuperclass 다")
    void 베이스엔티티() {
        부팅확인();
        Class<?> base = find("BaseEntity");
        if (base == null) {
            fail("BaseEntity 클래스가 없습니다. 등록일 · 수정일을 모아 둔 @MappedSuperclass BaseEntity 를 만드세요.");
        }
        assertTrue(base.isAnnotationPresent(MappedSuperclass.class),
                base.isAnnotationPresent(Entity.class)
                        ? "BaseEntity 에 @Entity 가 붙어 있습니다. 테이블과 매핑하지 않으므로 @MappedSuperclass 를 쓰세요."
                        : "BaseEntity 에 @MappedSuperclass 가 없습니다.");
    }

    @Test
    @DisplayName("[CH07] 모든 엔티티 테이블에 등록일 · 수정일 컬럼이 있다 (CREATED_DATE · LAST_MODIFIED_DATE)")
    void 등록일_수정일() throws Exception {
        부팅확인();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : ENTITY_TABLES.entrySet()) {
            Map<String, String> cols = 컬럼목록(e.getValue());
            List<String> lack = new ArrayList<>();
            for (String col : new String[]{"CREATED_DATE", "LAST_MODIFIED_DATE"}) {
                String type = cols.get(col);
                if (type == null) {
                    lack.add(col);
                } else if (!type.contains("TIMESTAMP") && !type.contains("DATE")) {
                    lack.add(col + "(날짜 타입 아님 : " + type + ")");
                }
            }
            if (!lack.isEmpty()) {
                problems.add(e.getKey() + "(" + e.getValue() + ") : " + lack);
            }
        }
        if (!problems.isEmpty()) {
            fail("등록일 · 수정일이 없는 테이블이 있습니다."
                    + "\n  BaseEntity 에 LocalDateTime createdDate · lastModifiedDate 를 두고"
                    + " Member · Order · OrderItem · Item · Delivery · Category 가 extends BaseEntity 하세요."
                    + "\n  - " + String.join("\n  - ", problems));
        }
    }
}
