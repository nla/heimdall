package heimdall.crawler;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class CrawlOptions
{
    private int concurrentWorkers;
    private long pageLoadTimeout;
    private long domStabilityCheckInterval;
    private int maxAncestorClickDepth;
    private boolean limitSingleConcurrentHitPerDomain;
    private String userAgent;
    
    private boolean useConstantDate;
    private String constantDateString; // JS date string
    
    private boolean useConstantRandom;
    private double constantRandomValue;
    
    private boolean includeLocalFileURIs;
    private boolean includeSubdomainsOfSeeds;
    private List<String> includeExternalDomainPatterns;
    private List<String> excludeExternalDomainPatterns;
    
    private boolean submitForms;
    
    public CrawlOptions()
    {
        this.concurrentWorkers = 0;
        this.pageLoadTimeout = 20000L;
        this.domStabilityCheckInterval = 1000L;
        this.maxAncestorClickDepth = 100;
        this.includeLocalFileURIs = false;
        this.includeSubdomainsOfSeeds = true;
        this.includeExternalDomainPatterns = new ArrayList<String>();
        this.excludeExternalDomainPatterns = new ArrayList<String>();
        this.submitForms = false;
        this.limitSingleConcurrentHitPerDomain = true;
        this.userAgent = null;
    }
    
    public CrawlOptions withConcurrentWorkers(int concurrentWorkers)
    {
        this.concurrentWorkers = concurrentWorkers;
        return this;
    }
    
    public CrawlOptions withPageLoadTimeout(long pageLoadTimeout)
    {
        this.pageLoadTimeout = pageLoadTimeout;
        return this;
    }
    
    public CrawlOptions withDomStabilityCheckInterval(long domStabilityCheckInterval)
    {
        this.domStabilityCheckInterval = domStabilityCheckInterval;
        return this;
    }
    
    public CrawlOptions withMaxAncestorClickDepth(int maxAncestorClickDepth)
    {
        this.maxAncestorClickDepth = maxAncestorClickDepth;
        return this;
    }
    
    public CrawlOptions withSeedSubdomainInclusionPolicy(boolean includeSubdomainsOfSeeds)
    {
        this.includeSubdomainsOfSeeds = includeSubdomainsOfSeeds;
        return this;
    }
    
    public CrawlOptions withLocalFileInclusionPolicy(boolean includeLocalFileURIs)
    {
        this.includeLocalFileURIs = includeLocalFileURIs;
        return this;
    }
    
    public CrawlOptions withConstantDateString(String constantDateString)
    {
        this.constantDateString = constantDateString;
        this.useConstantDate = true;
        return this;
    }
    
    public CrawlOptions withConstantRandomValue(double constantRandomValue)
    {
        this.constantRandomValue = constantRandomValue;
        this.useConstantRandom = true;
        return this;
    }
    
    public CrawlOptions withFormSubmissionPolicy(boolean submitForms)
    {
        this.submitForms = submitForms;
        return this;
    }
    
    public CrawlOptions withLimitSingleConcurrentHitPerDomain(boolean limitSingleConcurrentHitPerDomain)
    {
        this.limitSingleConcurrentHitPerDomain = limitSingleConcurrentHitPerDomain;
        return this;
    }
    
    public CrawlOptions withUserAgent(String userAgent)
    {
        this.userAgent = userAgent;
        return this;
    }
    
    public int getConcurrentWorkers()
    {
        return concurrentWorkers;
    }

    public long getPageLoadTimeout()
    {
        return pageLoadTimeout;
    }

    public long getDomStabilityCheckInterval()
    {
        return domStabilityCheckInterval;
    }

    public int getMaxAncestorClickDepth()
    {
        return maxAncestorClickDepth;
    }
    
    public boolean shouldUseConstantDateString()
    {
        return useConstantDate;
    }
    
    public boolean shouldUseConstantRandomValue()
    {
        return useConstantRandom;
    }
    
    public String getConstantDateString()
    {
        return constantDateString;
    }

    public double getConstantRandomValue()
    {
        return constantRandomValue;
    }

    public boolean shouldIncludeLocalFileURIs()
    {
        return includeLocalFileURIs;
    }

    public boolean shouldIncludeSubdomainsOfSeeds()
    {
        return includeSubdomainsOfSeeds;
    }

    public List<String> getIncludeExternalDomainPatterns()
    {
        return includeExternalDomainPatterns;
    }

    public List<String> getExcludeExternalDomainPatterns()
    {
        return excludeExternalDomainPatterns;
    }

    public boolean shouldSubmitForms()
    {
        return submitForms;
    }

    public boolean shouldLimitSingleConcurrentHitPerDomain()
    {
        return limitSingleConcurrentHitPerDomain;
    }
    
    public String getUserAgent()
    {
        return userAgent;
    }
    
    public static CrawlOptions fromProperties(Properties properties) throws Exception
    {
        CrawlOptions options = new CrawlOptions();
        
        for(Object key: properties.keySet())
        {
            String property = key.toString();
            String value = properties.getProperty(property);
            
            switch(property)
            {
                case "CONCURRENT_WORKERS":
                {
                    options.withConcurrentWorkers(Integer.parseInt(value));
                    break;
                }
                case "PAGE_LOAD_TIMEOUT":
                {
                    options.withPageLoadTimeout(Long.parseLong(value));
                    break;
                }
                case "DOM_STABILITY_CHECK_INTERVAL":
                {
                    options.withDomStabilityCheckInterval(Long.parseLong(value));
                    break;
                }
                case "MAX_ANCESTOR_CLICK_DEPTH":
                {
                    options.withMaxAncestorClickDepth(Integer.parseInt(value));
                    break;
                }
                case "SUBMIT_FORMS":
                {
                    options.withFormSubmissionPolicy(Boolean.parseBoolean(value));
                    break;
                }
                case "LIMIT_TO_SINGLE_CONCURRENT_HIT_PER_DOMAIN":
                {
                    options.withLimitSingleConcurrentHitPerDomain(Boolean.parseBoolean(value));
                    break;
                }
                case "USE_CONSTANT_DATE":
                {
                    if(Boolean.parseBoolean(value))
                    {
                        options.withConstantDateString(properties.getProperty("CONSTANT_DATE", "2024-01-01T00:00:00Z"));
                    }
                    
                    break;
                }
                case "USE_CONSTANT_RANDOM":
                {
                    if(Boolean.parseBoolean(value))
                    {
                        options.withConstantRandomValue(Double.parseDouble(properties.getProperty("CONSTANT_RANDOM", "0.5")));
                    }
                    
                    break;
                }
                case "INCLUDE_LOCAL_FILE_URIS":
                {
                    options.withLocalFileInclusionPolicy(Boolean.parseBoolean(value));
                    break;
                }
                case "INCLUDE_SUBDOMAINS_OF_SEEDS":
                {
                    options.withSeedSubdomainInclusionPolicy(Boolean.parseBoolean(value));
                    break;
                }
                case "EXTERNAL_DOMAIN_INCLUSION_PATTERNS":
                {
                    for(String pattern: value.split(properties.getProperty("DOMAIN_INCLUSION_PATTERN_DELIM", ";")))
                    {
                        if(!pattern.trim().isEmpty())
                        {
                            options.getIncludeExternalDomainPatterns().add(pattern.trim());
                        }
                    }
                    
                    break;
                }
                case "EXTERNAL_DOMAIN_EXLCLUSION_PATTERNS":
                {
                    for(String pattern: value.split(properties.getProperty("DOMAIN_INCLUSION_PATTERN_DELIM", ";")))
                    {
                        if(!pattern.trim().isEmpty())
                        {
                            options.getExcludeExternalDomainPatterns().add(pattern.trim());
                        }
                    }
                    
                    break;
                }
                case "USER_AGENT":
                {
                    options.withUserAgent(value);
                    break;
                }
            }
        }
        
        return options;
    }
}