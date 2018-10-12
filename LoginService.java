import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;
import org.openqa.selenium.*;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.firefox.FirefoxProfile;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * @Author Parisana
 */
@Slf4j
@Service
public class LoggingServiceImpl {

    private final String submitCaptchaURI = "http://2captcha.com/in.php";
    private final String getCaptchaResultURI = "http://2captcha.com/res.php";
    private final String apiKey = "YOUR_API_KEY";
    private final MongoTemplate mongoTemplate;
    private final TaskExecutor taskExecutor;
    private RestTemplate restTemplate = new RestTemplateBuilder().setConnectTimeout(50_000).build();

    public LoggingServiceImpl(MongoTemplate mongoTemplate,
                              TaskExecutor taskExecutor) {
        this.mongoTemplate = mongoTemplate;
        this.taskExecutor = taskExecutor;
    }

    public void startExecution(String username, String accountId, String url) {
        taskExecutor.execute(()->{

            final Account account;
            try {
                account = mongoTemplate.findOne(Query.query(Criteria.where(Account.class.getDeclaredField("id").getName()).is(accountId)), Account.class);
            } catch (NoSuchFieldException e) {
                e.printStackTrace();
                return;
            }

            if (account == null){
                log.error("Account with accountId: " + accountId + " not found.");
                return;
            }

            final String email = account.getEmail();
            final String password = account.getPassword();
            final StringBuilder proxyAddress = new StringBuilder(account.getDemoProxy().getAddress());

            if (proxyAddress.toString().isEmpty()){
                try {
                    proxyAddress.append(Objects.requireNonNull(mongoTemplate.findOne(new Query(Criteria.where(DemoProxy.class.getDeclaredField("status").getName()).is(0)),
                            DemoProxy.class)).getAddress());
                } catch (NoSuchFieldException e) {
                    e.printStackTrace();
                    return;
                }
            }

            WebDriver webDriver = initializeWebDriver(proxyAddress.toString(), url);
            try {
                tryMainLogin(webDriver, email, password, url);
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        });
    }

    private WebDriver initializeWebDriver(String proxyAddress, String url){
        System.setProperty("webdriver.gecko.driver", System.getProperty("user.home").concat("/geckodriver"));
        Proxy proxy = new Proxy();
        proxy.setHttpProxy(proxyAddress);
        FirefoxOptions capabilities = new FirefoxOptions();
        capabilities.setCapability(CapabilityType.PROXY, proxy);
        FirefoxProfile firefoxProfile = new FirefoxProfile();
        firefoxProfile.addExtension(new File(System.getProperty("user.home")
                .concat(File.separator)
                .concat("seleniumDrivers")
                .concat(File.separator)
                .concat("@canvas-shadow.xpi")));
//        firefoxProfile.setPreference();

        capabilities.setProfile(firefoxProfile);
        FirefoxDriver webDriver = new FirefoxDriver(capabilities);
        webDriver.setLogLevel(Level.ALL);

        webDriver.manage().window().maximize();//maximize
        webDriver.manage().timeouts().implicitlyWait(20, TimeUnit.SECONDS);//wait

        webDriver.get(url);
        return webDriver;
    }

    private CompletableFuture<Void> tryMainLogin(WebDriver webDriver, String username, String password, String url) throws IOException, InterruptedException {

        credentialsInitializer(webDriver, username, password);

        WebDriverWait webDriverWait = new WebDriverWait(webDriver, 10, 2000);
        webDriverWait.until(a -> webDriver.switchTo().frame(webDriver.findElement(By.cssSelector("iframe"))));
        String captchaResult;
        do{
            Thread.sleep(5_000);
            captchaResult = solveCaptcha(webDriver);
            System.out.println("Final result: " + captchaResult);
            String buttonText = webDriver.findElement(By.id("recaptcha-verify-button")).getText();
            if (buttonText.equalsIgnoreCase("skip")) {
                System.out.println("Skip button found!");
                webDriver
                        .findElement(By.id("recaptcha-verify-button")).click();
                captchaResult = "OK";
            }
        }while (captchaResult.startsWith("OK"));
        Thread.sleep(5_000);
        webDriver
                .findElement(By.id("recaptcha-verify-button")).click();
        if (webDriver.getCurrentUrl().startsWith(url.substring(10))){ // just check for the first 10 chars to see if its in the same page
            webDriver.navigate().refresh();
            tryMainLogin(webDriver.switchTo().defaultContent(), username, password, url);
        }

        return CompletableFuture.completedFuture(null);
    }

    private void credentialsInitializer(WebDriver webDriver, String username, String password) {
        webDriver.findElement(By.id("login-username")).clear();
        webDriver.findElement(By.id("login-username")).sendKeys(username);
        webDriver.findElement(By.id("login-password")).clear();
        webDriver.findElement(By.id("login-password")).sendKeys(password);
        webDriver.findElement(By.id("login-button")).click();
    }

    /**
     * Try solving captcha :
     * 1. get the frame screenshot
     * */
    private String solveCaptcha(WebDriver webDriver) throws IOException, InterruptedException {
        String result;

        String textInstructions = webDriver
                .findElement(By.id("rc-imageselect"))
                .findElement(By.className("rc-imageselect-desc-wrapper"))
                .findElement(By.xpath("//strong"))
                .getText();

        System.out.println("textInstructions: " + textInstructions);
        File screenshotFile = webDriver.findElement(By.xpath("//div[@id='rc-imageselect-target' and @class='rc-imageselect-target']")).getScreenshotAs(OutputType.FILE);
        try(ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            BufferedImage bufferedImage = ImageIO.read(screenshotFile);
            ImageIO.write(bufferedImage, "jpg", baos);

            String postForObject = restTemplate
                    .postForObject(submitCaptchaURI,
                            populatePostParams(textInstructions,
                            Objects.requireNonNull(baos.toByteArray(), "The size of byte array cannot be null")), String.class);

            copyToDisk(screenshotFile); // remove after test
            do{
                Thread.sleep(5000); // time-out for getting result
                if (postForObject==null)
                    throw new RuntimeException("post for captcha returned null!");
                result = restTemplate.getForObject(String.format(getCaptchaResultURI + "?key=%s&action=get&id=%s", apiKey, postForObject.split("\\|")[1]), String.class);
                if (result == null)
                    break;
                System.out.println("In do, result: "+ result);
            } while(!result.equals("ERROR_CAPTCHA_UNSOLVABLE") && !result.startsWith("OK"));

        }
        Assert.notNull(result, "Captcha result should not be null!");
        if (result.startsWith("OK")) {
            performClicks(webDriver, result);  // result = "OK|coordinates:x=135,y=51;x=131,y=232;x=215,y=66";
            Thread.sleep(2_000); // give some load time-out
        }
        return result;

    }

    /**
     * populate the post params map with all the required attributes.
     * */
    private Map<String, String> populatePostParams(String textInstructions, byte[] toByteArray) {
        Map<String, String> map = new HashMap<>();
        map.put("key", apiKey);
        map.put("method", "base64");
        map.put("coordinatescaptcha", "1");
        map.put("textinstructions", textInstructions);
        map.put("body", Base64.encodeBase64String(toByteArray));

        return map;
    }

    /**
     * Copy the screenshotFile to disk
     * */
    private void copyToDisk(File screenshotFile) {
        try {
            // Copy the element screenshot to disk
            File screenshotLocation = Paths.get(System.getProperty("user.home")).resolve("spotify").resolve("testUpload").resolve(UUID.randomUUID().toString()).toFile();
            FileCopyUtils.copy(screenshotFile, screenshotLocation);
        }catch (Exception e){
            log.error(e.getMessage());
        }
    }

    /**
     * Click on the co-ordinates given in the result
     * */
    private void performClicks(WebDriver webDriver, String result) throws InterruptedException {
        // OK|coordinates:x=135,y=51;x=131,y=232;x=215,y=66
        String[] xCommaY = result.split("\\|")[1].split(":")[1].split(";"); // has x=*, y=*
        WebElement frameWebDriverElement = webDriver.findElement(By.id("rc-imageselect"));
        Thread.sleep(2_000); // let the image to load

        Rectangle rect = frameWebDriverElement.getRect();

        Point midPoint= new Point(rect.getWidth()/2, rect.getHeight()/2-1); // rect has y+2 value

        List<Point> pointToClickList = Arrays.stream(xCommaY).map(e -> {
            String[] xAndY = e.split(",");
            //                    point.moveBy(startPoint.getX(), startPoint.getY());
            return new Point(Integer.parseInt(xAndY[0].split("=")[1]), Integer.parseInt(xAndY[1].split("=")[1]));
        }).collect(Collectors.toList());
        pointToClickList.forEach(e-> {
            try {
                System.out.println("Inside foreach loop: " + e);
                Thread.sleep((long) (Math.random() * 1_000));
                int x = e.getX();
                int y = e.getY();
                Actions actionsBuilder = new Actions(webDriver);
                actionsBuilder.moveToElement(frameWebDriverElement).moveByOffset(-midPoint.getX(), -midPoint.getY()).build().perform();
                actionsBuilder.moveByOffset(x, y).click().perform();
            } catch (InterruptedException e1) {
                e1.printStackTrace();
            }
        });
    }

}
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
class DemoProxy {
    @Id
    private String id;

    @Indexed(unique = true)
    private String address;

    // status values: 0=inactive, 1=active 7=blocked
    private byte status;
}
