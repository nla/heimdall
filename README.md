HEIMDALL WEB CRAWLER
====================

This web crawler is designed to tackle the problem of crawling and archiving JavaScript heavy sites. Such sites may modify the DOM, create new elements, or modify element attributes based on elements or sequences of elements that are interacted with. Some sites even render the entire DOM dynamically using JavaScript.

This is an issue for traditional web crawlers, which rely on scraping the HTML (and associated files) of pages or manually created scripts to perform specific actions for specific sites.

Heimdall will brute force its way through all viable click sequences for DOM elements that generate click events for each page it visits. Server responses within a Heimdall instance are cached so that each unique network request (URL plus data) is only processed once, which allows Heimdall to continuously reload the same page from cache and perform a new click sequence.

Heimdall controls headless instances of the Chrome web browser using Selenium. 

Running a crawl from the command line
-------------------------------------

Once packaged into a JAR, a crawl can be performed on the command line:

java -jar heimdall.jar <settings_file> <crawl_options_file> <seed_list_file>

for example:

java -jar heimdall.jar config/settings.properties config/crawl.properties config/seeds.txt


Settings file
-------------------------------------

The settings file specifies the server response cache implementation, and the HTTP call recorder implementation. The properties are as follows:

SERVER_RESPONSE_CACHE  
This is the fully qualified class name of the server response cache implementation. By default, OnDiskHashServerResponseCache will be used, which caches server responses to disk.

SERVER_RESPONSE_CACHE__CACHE_PATH  
Used by OnDiskHashServerResponseCache, specifies the directory in which server responses should be cached.

SERVER_RESPONSE_CACHE__CLEAR_ON_INIT  
Used by OnDiskHashServerResponseCache, specifies whether or not to delete the cache directory if it exists when the crawl starts.

CALL_RECORDER  
This is the fully qualified class name of the HTTP call recorder implementation. by default, heimdall.crawler.recorder.warc.WARCCallRecorder will be used, which records all HTTP calls into a single WARC file and generates a CDX file after the crawl.

CALL_RECORDER__WARC_PATH  
Used by WARCCallRecorder, specifies the file that the WARC data should be written to.

CALL_RECORDER__CDX_PATH  
Used by WARCCallRecorder, specifies the file that the CDX index should be written to.

REFERENCE_POLLING_INTERVAL  
Determines how often each worker will check the queue for work, in milliseconds. Defaults to 2000 (2 seconds).


Crawl options file
-------------------------------------

The crawl options file specifies some hyper parameters for the crawl:

CONCURRENT_WORKERS
How many concurrent workers are used to perform work. Each worker processes work on its own thread and controls a separate browser instance.

DOM_STABILITY_CHECK_INTERVAL
How often to check that the DOM has stabilised, in milliseconds. Whenever a page has finished loading, Heimdall continuously checks to see if the DOM has changed since the last time it checked as often as this interval. If the DOM hasn't changed between 2 checks, the DOM is considered stable, and processing continues.

PAGE_LOAD_TIMEOUT
How long to wait for a page to load before timing out. This includes the time to wait for the DOM to stablise (see DOM_STABILITY_CHECK_INTERVAL).

MAX_ANCESTOR_CLICK_DEPTH
The maximum number of clicks that can exist within a click sequence. This provides a maximum depth for Heimdall to generate sequences, preventing it from generating endless numbers of sequences for pages that generate infinite DOM changes from clicks (for example, an 'add row' button).

SUBMIT_FORMS
Whether or not Heimdall should submit forms.

LIMIT_TO_SINGLE_CONCURRENT_HIT_PER_DOMAIN
If true, Heimdall will never request more than 1 remote resource from the same domain at any given time.

USE_CONSTANT_DATE
If set to true, the value of CONSTANT_DATE will be used. Highly recommended.

CONSTANT_DATE
If USE_CONSTANT_DATE is true, specifies the date that will be returned by a site's JavaScript Date creations. This can be used to prevent calendars from generating different content based on which date is selected.

USE_CONSTANT_RANDOM
If set to true, the value of CONSTANT_RANDOM will be used. Highly recommended.

CONSTANT_RANDOM
If USE_CONSTANT_RANDOM is true, specifies the value that will be returned if a site's JavaScript generates a random value. This can be used to prevent random parameter values from being added to requests, as well as ensuring the same DOM resulting from the same click sequence.

INCLUDE_LOCAL_FILE_URIS
If set to true, allows the loading of local files (file:///). Can be useful for testing.

INCLUDE_SUBDOMAINS_OF_SEEDS
Determines whether or not subdomains of seed URLs should be crawled, if encountered.

DOMAIN_INCLUSION_PATTERN_DELIM
The delimiter for the EXTERNAL_DOMAIN_INCLUSION_PATTERNS and EXTERNAL_DOMAIN_EXCLUSION_PATTERNS properties.

EXTERNAL_DOMAIN_INCLUSION_PATTERNS
A list of Java regular expressions (separated by the value of DOMAIN_INCLUSION_PATTERN_DELIM). If an encountered domain matches one of these patterns, it will be crawled unless excluded.

EXTERNAL_DOMAIN_EXCLUSION_PATTERNS
A list of Java regular expressions (separated by the value of DOMAIN_INCLUSION_PATTERN_DELIM). If an encountered domain matches one of these patterns, it will not be crawled unless it is a seed domain or satisfies the criteria for the INCLUDE_SUBDOMAINS_OF_SEEDS property.

USER_AGENT
The user agent header to send with all requests.
