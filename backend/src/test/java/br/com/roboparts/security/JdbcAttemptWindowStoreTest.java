package br.com.roboparts.security;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcAttemptWindowStoreTest {
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    JdbcAttemptWindowStore one,two;
    @BeforeEach void database() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:windows-"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(source);jdbc.execute("CREATE SCHEMA roboparts");
        new ResourceDatabasePopulator(new ClassPathResource("db/cloud/schema-v1.sql")).execute(source);
        manager=new DataSourceTransactionManager(source);
        one=new JdbcAttemptWindowStore(jdbc,manager);two=new JdbcAttemptWindowStore(jdbc,manager);
    }
    @Test void instancesShareTheSameLimitAndTheWindowSurvivesBusinessRollback() {
        String hash=LoginAttemptLimiter.key("test@example.com");
        new TransactionTemplate(manager).executeWithoutResult(status->{
            assertThat(one.acquire("account",hash,1,60000,10).allowed()).isTrue();status.setRollbackOnly();
        });
        assertThat(two.acquire("account",hash,1,60000,10).allowed()).isFalse();
        assertThat(jdbc.queryForObject("SELECT identity_hash FROM roboparts.auth_attempt_windows",String.class)).isEqualTo(hash).doesNotContain("@");
    }
    @Test void oldSuccessCannotClearANewerWindowAndCapacityIsBounded() {
        String hash=LoginAttemptLimiter.key("account");
        var old=one.acquire("account",hash,1,60000,1);
        assertThat(two.acquire("account",LoginAttemptLimiter.key("other"),1,60000,1).allowed()).isFalse();
        jdbc.update("UPDATE roboparts.auth_attempt_windows SET expires_at=0");
        var newer=two.acquire("account",hash,1,60000,1);
        one.success("account",hash,old.generation());
        assertThat(one.acquire("account",hash,1,60000,1).allowed()).isFalse();
        two.success("account",hash,newer.generation());
        assertThat(one.acquire("account",hash,1,60000,1).allowed()).isTrue();
    }
    @Test void parallelReservationsAcrossInstancesCannotExceedTheLimit() throws Exception {
        try(var pool=Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> jobs=new ArrayList<>();
            for(int i=0;i<24;i++) {var store=i%2==0?one:two;jobs.add(()->store.acquire("ip",LoginAttemptLimiter.key("192.0.2.1"),5,60000,10).allowed());}
            long allowed=0;for(var future:pool.invokeAll(jobs))if(future.get())allowed++;
            assertThat(allowed).isEqualTo(5);
        }
    }
    @Test void limiterAdaptersUseSharedReservationsAndResetOnlySuccessfulAccounts() {
        var first=new LoginAttemptLimiter(Clock.systemUTC(),1,60,10,one);
        var second=new LoginAttemptLimiter(Clock.systemUTC(),1,60,10,two);
        var ticket=first.acquire(" PERSON@EXAMPLE.COM ");
        assertThat(second.acquire("person@example.com").allowed()).isFalse();
        first.success(ticket);assertThat(second.acquire("person@example.com").allowed()).isTrue();
        var ip1=new AuthRateLimiter(1,Duration.ofMinutes(1),10,one);
        var ip2=new AuthRateLimiter(1,Duration.ofMinutes(1),10,two);
        assertThat(ip1.accept("192.0.2.1").allowed()).isTrue();
        assertThat(ip2.accept("192.0.2.1").allowed()).isFalse();
    }
    @Test void unavailableStorageNeverGrantsAnAttempt() {
        jdbc.execute("DROP TABLE roboparts.auth_attempt_windows");
        assertThatThrownBy(()->one.acquire("account",LoginAttemptLimiter.key("test"),5,60000,10))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
