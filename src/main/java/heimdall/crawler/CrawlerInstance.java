package heimdall.crawler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptException;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.UnexpectedAlertBehaviour;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.devtools.NetworkInterceptor;
import org.openqa.selenium.devtools.RequestFailedException;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.ErrorHandler;
import org.openqa.selenium.remote.Response;
import org.openqa.selenium.remote.http.Filter;
import org.openqa.selenium.remote.http.HttpHandler;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.WebDriverWait;
import heimdall.crawler.cache.NotCachedException;
import heimdall.crawler.cache.ServerResponseCache;
import heimdall.crawler.http.HttpCall;
import heimdall.crawler.recorder.CallRecorder;
import thor.util.LockService;
import thor.util.LockedTask;

public class CrawlerInstance
{
    // Currently handling clicks to:
    // - All elements with a click listener
    // - All elements with onclick, href, or submit
    // - All elements whose click listeners change as a result of a click
    // - All elements whose href or onclick attributes change as a result of a click
    // - All newly added elements to the DOM after the final click of an ancestor path that satisfy the above
    
    private CallRecorder recorder;
    private ServerResponseCache serverResponseCache;
    private CrawlOptions crawlOptions;
    private long referencePollingInterval;
    private Consumer<Exception> errorListener;
    private LockService responseCacheLockService;
    private LockService politenessLockService;
    private List<String> seedDomains;
    
    private ConcurrentLinkedQueue<ElementReference> elementQueue;
    private ExecutorService executorService;
    private List<Worker> workers;
    
    private boolean disposed;
    
    public CrawlerInstance(CallRecorder recorder, ServerResponseCache serverResponseCache, 
            CrawlOptions crawlOptions, long referencePollingInterval, Consumer<Exception> errorListener)
    {
        this.recorder = recorder;
        this.serverResponseCache = serverResponseCache;
        this.crawlOptions = crawlOptions;
        this.referencePollingInterval = referencePollingInterval;
        this.errorListener = errorListener;
    }
    
    public void crawl(List<String> seedUrls)
    {
        disposed = false;
        seedDomains = new ArrayList<String>();
        
        for(String url: seedUrls)
        {
            try
            {
                URI uri = new URI(url);
                seedDomains.add(uri.getHost());
            }
            catch(URISyntaxException e){}
        }
        
        if(crawlOptions.shouldLimitSingleConcurrentHitPerDomain())
        {
            politenessLockService = new LockService();
        }
        
        responseCacheLockService = new LockService();
        elementQueue = new ConcurrentLinkedQueue<ElementReference>();
        
        for(String url: seedUrls)
        {
            elementQueue.add(new ElementReference(url, new ArrayList<String>()));
        }
        
        executorService = Executors.newFixedThreadPool(crawlOptions.getConcurrentWorkers());
        workers = new ArrayList<Worker>();
        CountDownLatch completionLatch = new CountDownLatch(crawlOptions.getConcurrentWorkers());
        AtomicInteger noWorkCounter = new AtomicInteger(0);
        
        for(int i=0; i<crawlOptions.getConcurrentWorkers(); i++)
        {
            executorService.execute(new Runnable(){
                public void run()
                {
                    boolean waitingForWork = false;
                    
                    try(Worker worker = new Worker())
                    {
                        workers.add(worker);
                        ElementReference reference;
                        
                        while(true)
                        {
                            if(worker.isBroken())
                            {
                                return;
                            }
                            
                            reference = elementQueue.poll();
                            
                            if(reference==null)
                            {
                                if((waitingForWork?noWorkCounter.get():noWorkCounter.incrementAndGet())==crawlOptions.getConcurrentWorkers())
                                {
                                    // No worker has any work and the queue is empty, so we are done!
                                    waitingForWork = true;
                                    return;
                                }
                                
                                waitingForWork = true;
                                Thread.sleep(referencePollingInterval);
                                continue;
                            }
                            else
                            {
                                if(waitingForWork)
                                {
                                    waitingForWork = false;
                                    noWorkCounter.decrementAndGet();
                                }
                            }
                            
                            worker.processReference(reference);
                        }
                    }
                    catch(Throwable e)
                    {
                        errorListener.accept(new Exception("Error from worker thread ["+Thread.currentThread().getId()+"]:", e));
                    }
                    finally
                    {
                        if(!waitingForWork)
                        {
                            noWorkCounter.incrementAndGet();
                        }
                        
                        completionLatch.countDown();
                    }
                }
            });
        }
        
        try
        {
            completionLatch.await();
        }
        catch(InterruptedException e){}
        
        dispose();
    }
    
    public void dispose()
    {
        if(disposed)
        {
            return;
        }
        
        disposed = true;
        executorService.shutdownNow();
        
        for(Worker worker: workers)
        {
            try
            {
                worker.close(); 
            }catch(Exception e){}
        }
        
        try
        {
            recorder.dispose();
        }
        catch(Exception e)
        {
            errorListener.accept(e);
        }
    }
    
    private boolean includeDomain(String domain)
    {
        for(String seedDomain: seedDomains)
        {
            if(domain.equals(seedDomain) || (crawlOptions.shouldIncludeSubdomainsOfSeeds() && domain.endsWith("."+seedDomain)))
            {
                //System.out.println("[DOMAIN CHECK] included seed domain "+domain+" | "+seedDomain);
                return true;
            }
        }
        
        for(String includePattern: crawlOptions.getIncludeExternalDomainPatterns())
        {
            if(domain.matches(includePattern))
            {
                for(String excludePattern: crawlOptions.getExcludeExternalDomainPatterns())
                {
                    if(domain.matches(excludePattern))
                    {
                        //System.out.println("[DOMAIN CHECK] excluded domain "+domain);
                        return false;
                    }
                }
                
                //System.out.println("[DOMAIN CHECK] included domain "+domain);
                return true;
            }
        }
        
        return false;
    }
    
    private class ElementReference
    {
        private String url;
        private List<String> ancestorPath;
        
        public ElementReference(String url, List<String> ancestorPath)
        {
            this.url = url;
            this.ancestorPath = ancestorPath;
        }
        
        public String getUrl()
        {
            return url;
        }

        public List<String> getAncestorPath()
        {
            return ancestorPath;
        }
        
        public String toString()
        {
            StringBuilder b = new StringBuilder();
            b.append(url).append(" @");
            
            for(String path: ancestorPath)
            {
                b.append(" [").append(path).append("]");
            }
            
            return b.toString();
        }
        
        public boolean equals(Object o)
        {
            return toString().equals(o.toString());
        }
    }
    
    public class DomStabilityCondition implements ExpectedCondition<Boolean> 
    {
        private ChromeDriver webDriver;
        private long checkInterval;

        public DomStabilityCondition(ChromeDriver webDriver, long checkInterval)
        {
            this.webDriver = webDriver;
            this.checkInterval = checkInterval;
        }
        
        public Boolean apply(WebDriver driver)
        {
            try
            {
                String initialDom = executeJavascript("return document.documentElement.outerHTML");
                Thread.sleep(checkInterval);
                String currentDom = executeJavascript("return document.documentElement.outerHTML");
                return initialDom.equals(currentDom);
            } 
            catch(Exception e)
            {
                errorListener.accept(e);
                return false;
            }
        }

        @SuppressWarnings("unchecked")
        private <T> T executeJavascript(String script)
        {
            return (T)((JavascriptExecutor)webDriver).executeScript(script);
        }
    }
    
    private class Worker implements AutoCloseable
    {
        private ChromeDriver webDriver;
        private NetworkInterceptor interceptor;
        private HashSet<String> resourceOmissionSet;
        private HashSet<String> resourceLoadedSet;
        private ReentrantLock resourceLock;
        private volatile boolean broken;
        
        public Worker() throws Exception
        {
            ChromeOptions chromeOptions = new ChromeOptions();
            chromeOptions.addArguments("--headless=new");
            chromeOptions.setCapability(CapabilityType.UNHANDLED_PROMPT_BEHAVIOUR, UnexpectedAlertBehaviour.DISMISS);
            
            LoggingPreferences logPrefs = new LoggingPreferences();
            logPrefs.enable(LogType.BROWSER, Level.ALL);
            chromeOptions.setCapability("goog:loggingPrefs", logPrefs);
            
            webDriver = new ChromeDriver(chromeOptions);
            webDriver.setErrorHandler(new ErrorHandler(){
                public boolean isIncludeServerErrors()
                {
                    return true;
                }
                
                public Response throwIfResponseFailed(Response response, long duration) throws RuntimeException
                {
                    return response;
                }
            });
            
//            DevTools devTools = webDriver.getDevTools();
//            devTools.createSession();
//            devTools.send(Page.enable());
            //devTools.send(Fetch.enable(Optional.empty(), Optional.empty()));
//            devTools.send(Network.enable(Optional.empty(), Optional.empty(), Optional.empty()));
            
            String injectionSource = SCRIPT__INJECTION;
            
            if(crawlOptions.shouldUseConstantDateString())
            {
                injectionSource = injectionSource
                        +"Date = (function() {return new Date('"+crawlOptions.getConstantDateString()+"');})()\n";
            }
            if(crawlOptions.shouldUseConstantRandomValue())
            {
                injectionSource = injectionSource
                        +"Math.random = function(){ return "+crawlOptions.getConstantRandomValue()+"; };\n";
            }
            
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("source", injectionSource);
            webDriver.executeCdpCommand("Page.addScriptToEvaluateOnNewDocument", params);
            
            resourceOmissionSet = new HashSet<String>();
            resourceLoadedSet = new HashSet<String>();
            resourceLock = new ReentrantLock();
            
            interceptor = new NetworkInterceptor(webDriver, new Filter(){
                public HttpHandler apply(HttpHandler original)
                {
                    return new HttpHandler(){
                        public HttpResponse execute(HttpRequest req) throws UncheckedIOException
                        {
                            //System.out.println("["+Worker.this.toString()+" - "+Thread.currentThread().getId()+"] Exec req "+req.getUri());
                            req.setHeader("Accept-Encoding", "");
                            
                            if(crawlOptions.getUserAgent()!=null)
                            {
                                req.setHeader("User-Agent", crawlOptions.getUserAgent());
                            }
                            
                            try
                            {
                                return responseCacheLockService.runLockedTask(URIUtils.stripURLofFragment(req.getUri()), new LockedTask<HttpResponse>(){
                                    public HttpResponse execute() throws Exception
                                    {
                                        //System.out.println("["+Worker.this.toString()+" - "+Thread.currentThread().getId()+"] Locked task req "+req.getUri());
                                        HttpCall call;
                                        
                                        try
                                        {
                                            call = serverResponseCache.load(req);
                                            //System.out.println("["+Worker.this.toString()+" - "+Thread.currentThread().getId()+"] Get cached req "+req.getUri());
                                        }
                                        catch(NotCachedException c)
                                        {
                                            HttpResponse response;
                                            
                                            try
                                            {
                                                //System.out.println("["+Worker.this.toString()+" - "+Thread.currentThread().getId()+"] Non cached req "+req.getUri());
                                                resourceLock.lock();
                                                
                                                try
                                                {
                                                    resourceLoadedSet.add(URIUtils.stripURLofFragment(req.getUri()));
                                                }
                                                finally
                                                {
                                                    resourceLock.unlock();
                                                }
                                                
                                                if(crawlOptions.shouldLimitSingleConcurrentHitPerDomain())
                                                {
                                                    //System.out.println("["+Worker.this.toString()+" - "+Thread.currentThread().getId()+"] polite URI "+req.getUri());
                                                    URI uri = new URI(req.getUri());
                                                    
                                                    //System.out.println("["+Worker.this.toString()+" - "+Thread.currentThread().getId()+"] polite lock "+req.getUri());
                                                    response = politenessLockService.runLockedTask(uri.getHost(), new LockedTask<HttpResponse>(){
                                                        public HttpResponse execute() throws Exception
                                                        {
                                                            //System.out.println("["+Worker.this.toString()+" - "+Thread.currentThread().getId()+"] GETTING server req "+req.getUri());
                                                            
                                                            try
                                                            {
                                                                return original.execute(req);
                                                            }
                                                            catch(Exception e)
                                                            {
                                                                //System.out.println("["+Worker.this.toString()+" - "+Thread.currentThread().getId()+"] ERROR server req "+req.getUri());
                                                                throw e;
                                                            }
                                                            finally
                                                            {
                                                                //System.out.println("["+Worker.this.toString()+" - "+Thread.currentThread().getId()+"] RETURN server req "+req.getUri());
                                                            }
                                                        }
                                                    });
                                                }
                                                else
                                                {
                                                    response = original.execute(req);
                                                }
                                            }
                                            catch(RequestFailedException e)
                                            {
                                                //System.out.println("["+Worker.this.toString()+" - "+Thread.currentThread().getId()+"] REQ FAILED "+req.getUri());
                                                //e.printStackTrace(System.out);
                                                resourceLock.lock();
                                                
                                                try
                                                {
                                                    //System.out.println("["+Worker.this.toString()+" - "+Thread.currentThread().getId()+"] Added omission "+req.getUri());
                                                    resourceOmissionSet.add(req.getUri());
                                                }
                                                finally
                                                {
                                                    resourceLock.unlock();
                                                }
                                                
                                                throw e;
                                            }
                                            
                                            call = new HttpCall(req, response);
                                            
                                            try
                                            {
                                                serverResponseCache.save(call);
                                                recorder.recordCall(call);
                                            }
                                            catch(Exception e)
                                            {
                                                errorListener.accept(e);
                                                broken = true;
                                            }
                                        }
                                        catch(Exception e)
                                        {
                                            errorListener.accept(new Exception(e));
                                            broken = true;
                                            throw e;
                                        }
                                        if(!call.getResponse().isSuccessful() && call.getResponse().getStatus()!=302)
                                        {
                                            resourceLock.lock();
                                            
                                            try
                                            {
                                                resourceOmissionSet.add(req.getUri());
                                            }
                                            finally
                                            {
                                                resourceLock.unlock();
                                            }
                                        }
                                        
                                        return call.getResponse();
                                    }
                                });
                            }
                            catch(RequestFailedException e)
                            {
                                throw e;
                            }
                            catch(UncheckedIOException e)
                            {
                                throw e;
                            }
                            catch(Exception e)
                            {
                                throw new UncheckedIOException(e.getMessage(), new IOException(e));
                            }
                        }
                    };
                }
            });
        }
        
        public void processReference(ElementReference reference) throws Exception
        {
            System.out.println("PROCESSING "+reference);
            webDriver.get(reference.getUrl());
            waitForDOM();
            
            resourceLock.lock();
            
            try
            {
                if(resourceOmissionSet.contains(reference.getUrl()))
                {
                    // This page was marked for omission, so don't crawl it
                    // This is usually because it returned a bad response code or failed to load at all
                    return;
                }
            }
            finally
            {
                resourceOmissionSet.clear();
                resourceLock.unlock();
            }
            
            if(reference.getAncestorPath().isEmpty())
            {
                // Base URL, register all clickable elements
                
                detectClickableElements(reference.getAncestorPath(), true);
            }
            else
            {
                // Clicking sequence, only register elements added to the DOM by the final click
                // or register a new page URL if navigating away from the page
                
                try
                {
                    for(int i=0; i<reference.getAncestorPath().size(); i++)
                    {
                        String path = reference.getAncestorPath().get(i);
                        WebElement element = getWebElementAtAncestorPath(path);
                        
                        if(element==null)
                        {
                            throw new Exception("CANNOT FIND ELEMENT AT "+path);
                        }
                        
                        if(i==reference.getAncestorPath().size()-1)
                        {
                            webDriver.executeScript(SCRIPT__MUTATION_OBSERVER);
                        }
                        
                        resourceLock.lock();
                        
                        try
                        {
                            resourceLoadedSet.clear();
                        }
                        finally
                        {
                            resourceLock.unlock();
                        }
                        
                        clickElement(element);
                        waitForDOM();
                        
                        if(!URIUtils.stripURLofFragment(webDriver.getCurrentUrl()).equals(URIUtils.stripURLofFragment(reference.getUrl())))
                        {
                            resourceLock.lock();
                            
                            try
                            {
                                if(resourceLoadedSet.contains(URIUtils.stripURLofFragment(webDriver.getCurrentUrl())))
                                {
                                    // New URL, if the domain should be included, add a new reference with an empty ancestor path
                                    
                                    URI uri = new URI(webDriver.getCurrentUrl());
                                    String domain = uri.getHost();
                                    
                                    if((uri.getScheme().startsWith("http") && includeDomain(domain))
                                            || (uri.getScheme().startsWith("file") && crawlOptions.shouldIncludeLocalFileURIs()))
                                    {
                                        //System.out.println("New url detected: "+webDriver.getCurrentUrl());
                                        registerElementReference(new ElementReference(webDriver.getCurrentUrl(), new ArrayList<String>()));
                                    }
                                }
                            }
                            finally
                            {
                                resourceLock.unlock();
                            }
                            
                            return;
                        }
                    }
                    if(crawlOptions.getMaxAncestorClickDepth()==0 || reference.getAncestorPath().size()<=crawlOptions.getMaxAncestorClickDepth())
                    {
                        detectClickableElements(reference.getAncestorPath(), false);
                    }
                }
                catch(Exception e)
                {
                    errorListener.accept(e);
                    return;
                }
            }
        }
        
        private void clickElement(WebElement element)
        {
            webDriver.executeScript(SCRIPT__CLICK_ELEMENT, element);
        }
        
        private void waitForDOM()
        {
            //System.out.println("Waiting for dom");
            WebDriverWait wait = new WebDriverWait(webDriver, Duration.ofMillis(crawlOptions.getPageLoadTimeout()));
            wait.ignoring(Exception.class);
            wait.until(new DomStabilityCondition(webDriver, crawlOptions.getDomStabilityCheckInterval()));
            //System.out.println("Finished waiting for dom");
        }
        
        @SuppressWarnings("unchecked")
        private void detectClickableElements(List<String> parentAncestorPath, boolean initialPageLoad)
        {
            // Build a list of elements that are candidates to be clicked
            
            List<WebElement> elements = new ArrayList<WebElement>();
            elements.addAll((List<WebElement>)webDriver.executeScript(SCRIPT__SELECT_ELEMENTS_WITH_CLICK_LISTENERS));
            elements.addAll((List<WebElement>)webDriver.executeScript(SCRIPT__SELECT_ELEMENTS_WITH_CLICKABLE_ATTRIBUTES));
            
            if(crawlOptions.shouldSubmitForms())
            {
                elements.addAll(webDriver.findElements(By.xpath("//*[@type=\"submit\"]")));
            }
            
//            for(LogEntry entry: webDriver.manage().logs().get(LogType.BROWSER).getAll())
//            {
//                System.out.println("[BROWSERLOG] "+entry);
//            }
            
            String currentAncestorPath = null;
            boolean currentlyClickedElementStillViable = false;
            
            List<String> mutationAncestorPaths = new ArrayList<String>();
            boolean attributeMutationOccurred = false;
            
            if(!initialPageLoad)
            {
                currentAncestorPath = parentAncestorPath.get(parentAncestorPath.size()-1);
                
                for(WebElement element: (List<WebElement>)webDriver.executeScript(SCRIPT__SELECT_CLICKABLE_OR_ADDED_MUTATION_ELEMENTS))
                {
                    mutationAncestorPaths.add(createAncestorPath(element));
                }
                
                attributeMutationOccurred = (Boolean)webDriver.executeScript(SCRIPT__DID_ANY_ATTRIBUTE_MUTATION_OCCUR);
            }
            
            //System.out.println("Detected "+elements.size()+" elements");
            
            for(WebElement element: elements)
            {
               // System.out.println("Element: "+element);
                // Ignore unwanted href protocols
                
                String href = element.getAttribute("href");
                
                if(href!=null && href.matches("(tel|mailto|ftp|file|sms|geo|callto|sip):.+"))
                {
                    continue;
                }
                
                //
                
                String elementAncestorPath = createAncestorPath(element);
                
                if(!initialPageLoad && elementAncestorPath.equals(currentAncestorPath))
                {
                    currentlyClickedElementStillViable = true;
                }
                if(initialPageLoad || mutationAncestorPaths.contains(elementAncestorPath))
                {
                    List<String> ancestorPath = new ArrayList<String>();
                    ancestorPath.addAll(parentAncestorPath);
                    ancestorPath.add(elementAncestorPath);
                    
                    //System.out.println("Adding element: ["+element.getTagName()+"] "+element.getAttribute("href")+" | "+element.getAttribute("outerHTML"));
                    registerElementReference(new ElementReference(webDriver.getCurrentUrl(), ancestorPath));
                }
            }
            if(currentlyClickedElementStillViable && !initialPageLoad && (attributeMutationOccurred || !mutationAncestorPaths.isEmpty()))
            {
                // The DOM was modified as a result of the this click, therefore, register this element again
                // in case the dom will change the next time it is clicked as well
                List<String> ancestorPath = new ArrayList<String>();
                ancestorPath.addAll(parentAncestorPath);
                ancestorPath.add(currentAncestorPath);
                registerElementReference(new ElementReference(webDriver.getCurrentUrl(), ancestorPath));
            }
        }
        
        private void registerElementReference(ElementReference reference)
        {
            System.out.println("\t-added "+reference);
            elementQueue.add(reference);
        }
        
        private String createAncestorPath(WebElement element)
        {
            return executeScript(SCRIPT__BUILD_ANCESTOR_PATH, element);
        }
        
        private WebElement getWebElementAtAncestorPath(String path) throws Exception
        {
            try
            {
                return executeScript(SCRIPT__GET_ELEMENT_AT_ANCESTOR_PATH, path);
            }
            catch(JavascriptException e)
            {
                throw new Exception("Error finding element at path ["+path+"]:", e);
            }
        }
        
        @SuppressWarnings("unchecked")
        private <T> T executeScript(String script, Object... args) throws JavascriptException
        {
            Object result = webDriver.executeScript(script, args);
            
            if(result==null)
            {
                return null;
            }
            if(result.getClass().equals(JavascriptException.class))
            {
                throw (JavascriptException)result;
            }
            
            return (T)result;
        }
        
        public boolean isBroken()
        {
            return broken;
        }
        
        public void close() throws Exception
        {
            interceptor.close();
            webDriver.quit();
        }
    }
    
    private static final String SCRIPT__INJECTION = ""+
            "window.heimdallClickableOrAddedMutationDomElements = [];\n"+
            "window.heimdallAllAttributeMutationDomElements = [];\n"+
            "window.heimdallElementsWithClickEvents = [];\n"+
            "var heimdallOriginalAddEventListener = EventTarget.prototype.addEventListener;\n" +
            "EventTarget.prototype.addEventListener = function(type, listener, options) {\n" +
            "    if (type === 'click' && listener) {\n" +
            "        window.heimdallElementsWithClickEvents.push(this);\n" +
            "        if(window.heimdallAddedDomElements)\n"+
            "        {\n"+
            "           window.heimdallAddedDomElements.push(this);\n"+
            "        }\n"+
            "    }\n" +
            "    return heimdallOriginalAddEventListener.call(this, type, listener, options);\n" +
            "};\n"+
            "";
    
    private static final String SCRIPT__SELECT_ELEMENTS_WITH_CLICK_LISTENERS = 
            "return window.heimdallElementsWithClickEvents;";
    
    private static final String SCRIPT__SELECT_ELEMENTS_WITH_CLICKABLE_ATTRIBUTES = ""+
            "var elements = document.querySelectorAll('*');\n" +
            "var elementsWithClickListeners = [];\n" +
            "elements.forEach(function(element) {\n" +
            "    if (element.tagName.toLowerCase()!='link' && (element.onclick || element.hasAttribute('onclick') || element.href || element.hasAttribute('href')))\n"+
            "    {\n"+
            "        elementsWithClickListeners.push(element);\n" +
            "    }\n"+
            "})\n;" +
            "return elementsWithClickListeners;";
    
    private static final String SCRIPT__GET_ELEMENT_AT_ANCESTOR_PATH = ""+
            "var ancestorPath = arguments[0].split('>');\n"+
            "var currentElement = document.body;\n"+
            "for(var i=2; i<ancestorPath.length-1; i++)\n"+
            "{\n"+
            "    currentElement = currentElement.children[parseInt(ancestorPath[i])];\n"+
            "}\n"+
            "return currentElement;\n"+
            "";
    
    private static final String SCRIPT__BUILD_ANCESTOR_PATH = ""+
            "var element = arguments[0];\n"+
            "var aString = '';\n"+
            "while(element.parentNode!=null)\n"+
            "{\n"+
            "    aString = Array.from(element.parentNode.children).indexOf(element)+'>'+aString;\n"+
            "    element = element.parentNode;\n"+
            "}\n"+
            "return aString;\n"+
            "";
    
    private static final String SCRIPT__MUTATION_OBSERVER = ""+
            "window.heimdallClickableOrAddedMutationDomElements = [];\n"+
            "window.heimdallAllAttributeMutationDomElements = [];\n"+
            "var observerOptions = { childList: true, subtree: true, attributes: true, attributeFilter: ['href', 'onclick'] };\n"+
            "var observer = new MutationObserver(function(mutationsList, observer){\n"+
            "    for(var mutation of mutationsList)\n"+
            "    {\n"+
            "        if(mutation.type === 'childList' && mutation.addedNodes.length > 0)"+
            "        {\n"+
            "            for(var i=0; i<mutation.addedNodes.length; i++)\n"+
            "            {\n"+
            "                if(mutation.addedNodes[i].nodeType === Node.ELEMENT_NODE)\n"+
            "                {\n"+
            "                    window.heimdallClickableOrAddedMutationDomElements.push(mutation.addedNodes[i]);\n"+
            "                }\n"+
            "            }\n"+
            "        }\n"+
            "        if(mutation.type === 'attributes')\n"+
            "        {\n"+
            "            window.heimdallClickableOrAddedMutationDomElements.push(mutation.target);\n"+
            "        }\n"+
            "    }\n"+
            "});\n"+
            "observer.observe(document.body, observerOptions);\n"+
            "observerOptions = { attributes: true };\n"+
            "observer = new MutationObserver(function(mutationsList, observer){\n"+
            "    for(var mutation of mutationsList)\n"+
            "    {\n"+
            "        if(mutation.type === 'attributes')\n"+
            "        {\n"+
            "            window.heimdallAllAttributeMutationDomElements.push(mutation.target);\n"+
            "        }\n"+
            "    }\n"+
            "});\n"+
            "observer.observe(document.body, observerOptions);\n"; 
    
    private static final String SCRIPT__SELECT_CLICKABLE_OR_ADDED_MUTATION_ELEMENTS = 
            "return window.heimdallClickableOrAddedMutationDomElements;";
    private static final String SCRIPT__DID_ANY_ATTRIBUTE_MUTATION_OCCUR = 
            "return window.heimdallAllAttributeMutationDomElements.length > 0;";
    
    private static final String SCRIPT__CLICK_ELEMENT = 
            "var element = arguments[0];\n"+
            "if(element.disabled)\n"+
            "{\n"+
            "   element.disabled = false;\n"+
            "}\n"+
            "element.click();\n"+
            "";
    
}