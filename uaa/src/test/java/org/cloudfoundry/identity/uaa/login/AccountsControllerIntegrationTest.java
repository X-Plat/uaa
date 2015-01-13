package org.cloudfoundry.identity.uaa.login;

import com.dumbster.smtp.SimpleSmtpServer;
import com.googlecode.flyway.core.Flyway;
import org.cloudfoundry.identity.uaa.authentication.Origin;
import org.cloudfoundry.identity.uaa.authentication.UaaPrincipal;
import org.cloudfoundry.identity.uaa.codestore.JdbcExpiringCodeStore;
import org.cloudfoundry.identity.uaa.test.YamlServletProfileInitializerContextInitializer;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.common.util.RandomValueStringGenerator;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.XmlWebApplicationContext;

import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

public class AccountsControllerIntegrationTest {

    XmlWebApplicationContext webApplicationContext;

    private MockMvc mockMvc;
    private static SimpleSmtpServer mailServer;
    private String userEmail;

    @BeforeClass
    public static void startMailServer() throws Exception {
        mailServer = SimpleSmtpServer.start(2525);
    }

    @Before
    public void setUp() throws Exception {
        webApplicationContext = new XmlWebApplicationContext();
        webApplicationContext.setEnvironment(new MockEnvironment());
        new YamlServletProfileInitializerContextInitializer().initializeContext(webApplicationContext, "uaa.yml,login.yml");
        webApplicationContext.setConfigLocation("file:./src/main/webapp/WEB-INF/spring-servlet.xml");
        webApplicationContext.refresh();
        FilterChainProxy springSecurityFilterChain = webApplicationContext.getBean("springSecurityFilterChain", FilterChainProxy.class);

        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .addFilter(springSecurityFilterChain)
            .build();

        userEmail = "user" +new RandomValueStringGenerator().generate()+ "@example.com";
        Assert.assertNotNull(webApplicationContext.getBean("messageService"));
    }

    @After
    public void tearDown() throws Exception {
        Flyway flyway = webApplicationContext.getBean(Flyway.class);
        flyway.clean();
    }

    @AfterClass
    public static void stopMailServer() throws Exception {
        mailServer.stop();
    }

    @Test
    public void testCreateActivationEmailPage() throws Exception {
        ((MockEnvironment) webApplicationContext.getEnvironment()).setProperty("login.brand", "oss");

        mockMvc.perform(get("/create_account.do"))
                .andExpect(content().string(containsString("Create your account")))
                .andExpect(content().string(not(containsString("Pivotal ID"))));
    }

    @Test
    public void testCreateActivationEmailPageWithPivotalBrand() throws Exception {
        ((MockEnvironment) webApplicationContext.getEnvironment()).setProperty("login.brand", "pivotal");

        mockMvc.perform(get("/create_account.do"))
            .andExpect(content().string(containsString("Create your Pivotal ID")))
            .andExpect(content().string(not(containsString("Create your account"))));
    }

    @Test
    public void testActivationEmailSentPage() throws Exception {
        ((MockEnvironment) webApplicationContext.getEnvironment()).setProperty("login.brand", "oss");

        mockMvc.perform(get("/accounts/email_sent"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Create your account")))
                .andExpect(xpath("//input[@disabled='disabled']/@value").string("Email successfully sent"))
                .andExpect(content().string(not(containsString("Pivotal ID"))));
    }

    @Test
    public void testActivationEmailSentPageWithPivotalBrand() throws Exception {
        ((MockEnvironment) webApplicationContext.getEnvironment()).setProperty("login.brand", "pivotal");

        mockMvc.perform(get("/accounts/email_sent"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Create your Pivotal ID")))
                .andExpect(xpath("//input[@disabled='disabled']/@value").string("Email successfully sent"))
                .andExpect(content().string(not(containsString("Create your account"))));
    }

    @Test
    public void testCreatingAnAccount() throws Exception {
        PredictableGenerator generator = new PredictableGenerator();
        JdbcExpiringCodeStore store = webApplicationContext.getBean(JdbcExpiringCodeStore.class);
        store.setGenerator(generator);

        mockMvc.perform(post("/create_account.do")
                    .param("email", userEmail)
                    .param("password", "secret")
                    .param("password_confirmation", "secret")
                    .param("client_id", "app"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("accounts/email_sent"));

        MvcResult mvcResult = mockMvc.perform(get("/verify_user")
                .param("code", "test"+generator.counter.get()))
            .andDo(print())
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("http://localhost:8080/app/"))
            .andReturn();

        SecurityContext securityContext = (SecurityContext) mvcResult.getRequest().getSession().getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        Authentication authentication = securityContext.getAuthentication();
        Assert.assertThat(authentication.getPrincipal(), instanceOf(UaaPrincipal.class));
        UaaPrincipal principal = (UaaPrincipal) authentication.getPrincipal();
        Assert.assertThat(principal.getEmail(), equalTo(userEmail));
        Assert.assertThat(principal.getOrigin(), equalTo(Origin.UAA));
    }

    public static class PredictableGenerator extends RandomValueStringGenerator {
        public AtomicInteger counter = new AtomicInteger(1);
        @Override
        public String generate() {
            return  "test"+counter.incrementAndGet();
        }
    }
}