/**
 * Copyright wro4j@2011
 */
package ro.isdc.wro.model.group.processor;

import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.Assert;

import org.apache.commons.io.IOUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ro.isdc.wro.WroRuntimeException;
import ro.isdc.wro.config.Context;
import ro.isdc.wro.config.jmx.WroConfiguration;
import ro.isdc.wro.manager.factory.BaseWroManagerFactory;
import ro.isdc.wro.manager.factory.WroManagerFactory;
import ro.isdc.wro.model.resource.Resource;
import ro.isdc.wro.model.resource.ResourceType;
import ro.isdc.wro.model.resource.locator.UriLocator;
import ro.isdc.wro.model.resource.locator.factory.SimpleUriLocatorFactory;
import ro.isdc.wro.model.resource.locator.factory.UriLocatorFactory;
import ro.isdc.wro.model.resource.processor.ResourcePreProcessor;
import ro.isdc.wro.model.resource.processor.decorator.CopyrightKeeperProcessorDecorator;
import ro.isdc.wro.model.resource.processor.factory.SimpleProcessorsFactory;
import ro.isdc.wro.model.resource.processor.impl.js.JSMinProcessor;
import ro.isdc.wro.util.StopWatch;
import ro.isdc.wro.util.WroUtil;


/**
 * @author Alex Objelean
 */
public class TestPreProcessorExecutor {
  private static final Logger LOG = LoggerFactory.getLogger(TestPreProcessorExecutor.class);
  @Mock
  private HttpServletRequest mockRequest;
  @Mock
  private HttpServletResponse mockResponse;
  @Mock
  private FilterConfig mockFilterConfig;
  @Mock
  private ServletContext mockServletContext;
  @Mock
  private UriLocatorFactory mockLocatorFactory;
  @Mock
  private UriLocator mockLocator;
  private PreProcessorExecutor executor;


  @Before
  public void setUp() throws Exception {
    initMocks(this);
    
    when(mockRequest.getRequestURL()).thenReturn(new StringBuffer(""));
    when(mockRequest.getServletPath()).thenReturn("");
    when(mockFilterConfig.getServletContext()).thenReturn(mockServletContext);
    when(mockLocatorFactory.locate(Mockito.anyString())).thenReturn(WroUtil.EMPTY_STREAM);
    when(mockLocator.locate(Mockito.anyString())).thenReturn(WroUtil.EMPTY_STREAM);
    when(mockLocatorFactory.getInstance(Mockito.anyString())).thenReturn(mockLocator);
    
    final Context context = Context.webContext(mockRequest, mockResponse, mockFilterConfig);
    Context.set(context);
    //force parallel execution
    Context.get().getConfig().setParallelPreprocessing(true);
    Context.get().getConfig().setIgnoreFailingProcessor(true);
    initExecutor();
  }


  private WroManagerFactory createWroManager(final ResourcePreProcessor... preProcessors) {
    final SimpleProcessorsFactory processorsFactory = new SimpleProcessorsFactory();
    for (final ResourcePreProcessor resourcePreProcessor : preProcessors) {
      processorsFactory.addPreProcessor(resourcePreProcessor);
    }
    final BaseWroManagerFactory wroManagerFactory = new BaseWroManagerFactory();
    wroManagerFactory.setProcessorsFactory(processorsFactory);
    wroManagerFactory.setUriLocatorFactory(mockLocatorFactory);
    return wroManagerFactory;
  }


  /**
   * @param wroManagerFactory
   */
  private void initExecutor(final ResourcePreProcessor... preProcessors) {
    final WroManagerFactory wroManagerFactory = createWroManager(preProcessors);
    final Injector injector = InjectorBuilder.create(wroManagerFactory).build();
    executor = new PreProcessorExecutor();
    injector.inject(executor);
  }


  @Test(expected = NullPointerException.class)
  public void cannotAcceptNullArguments()
    throws Exception {
    executor.processAndMerge(null, true);
  }


  /**
   * Creates a slow pre processor which sleeps for a given amount of milliseconds and doesn't change the processed
   * content.
   */
  private ResourcePreProcessor createSlowPreProcessor(final long time) {
    return new ResourcePreProcessor() {
      public void process(final Resource resource, final Reader reader, final Writer writer)
        throws IOException {
        try {
          IOUtils.copy(reader, writer);
          Thread.sleep(time);
        } catch (final InterruptedException e) {
        }
      }
    };
  }


  private ResourcePreProcessor createProcessorUsingMissingResource() {
    return new ResourcePreProcessor() {
      public void process(final Resource resource, final Reader reader, final Writer writer)
        throws IOException {
        LOG.debug("executing processor which will throw IOException");
        throw new IOException("Invalid resource found!");
      }
    };
  }


  private ResourcePreProcessor createProcessorWhichFails() {
    return new ResourcePreProcessor() {
      public void process(final Resource resource, final Reader reader, final Writer writer)
        throws IOException {
        LOG.debug("executing failing processor...");
        throw new WroRuntimeException("Boom!");
      }
    };
  }


  @Test
  public void processEmptyList()
    throws Exception {
    final List<Resource> resources = new ArrayList<Resource>();
    Assert.assertEquals("", executor.processAndMerge(resources, true));
    Assert.assertEquals("", executor.processAndMerge(resources, false));
  }


  @Test
  public void shouldNotFailWhenNoResourcesProcessed()
    throws Exception {
    initExecutor(createProcessorUsingMissingResource());
    executor.processAndMerge(createResources(), true);
  }


  private List<Resource> createResources(final Resource... resources) {
    final List<Resource> resourcesList = new ArrayList<Resource>();
    for (final Resource resource : resources) {
      resourcesList.add(resource);
    }
    return resourcesList;
  }


  @Test(expected = IOException.class)
  public void shouldFailWhenProcessingInvalidResource()
    throws Exception {
    when(mockLocator.locate(Mockito.anyString())).thenThrow(IOException.class);
    Context.get().getConfig().setIgnoreMissingResources(false);
    shouldNotFailWhenProcessingInvalidResource();
  }

  @Test
  public void shouldNotFailWhenProcessingInvalidResource()
    throws IOException {
    initExecutor(createProcessorUsingMissingResource());
    final List<Resource> resources = createResources(Resource.create("/uri", ResourceType.JS));
    final String result = executor.processAndMerge(resources, true);
    Assert.assertEquals("", result);
  }


  @Test(expected = WroRuntimeException.class)
  public void shouldFailWhenUsingFailingPreProcessor()
    throws Exception {
    genericUseFailingPreProcessorWithIngoreFlag(false);
  }
  
  @Test
  public void shouldNotFailWhenUsingFailingPreProcessor()
      throws Exception {
    genericUseFailingPreProcessorWithIngoreFlag(true);
  }

  private void genericUseFailingPreProcessorWithIngoreFlag(final boolean ignoreFlag) throws Exception {
    Context.get().getConfig().setIgnoreFailingProcessor(ignoreFlag);
    initExecutor(createProcessorWhichFails());
    final List<Resource> resources = createResources(Resource.create("", ResourceType.JS));
    final String result = executor.processAndMerge(resources, true);
    Assert.assertEquals("", result);

  }
  
  /**
   * This test should work when running at least on dual-core.
   * It assumes that (P1(r1) + P2(r1) + P3(r1)) + (P1(r2) + P2(r2) + P3(r2)) > Parallel(P1(r1) + P2(r1) + P3(r1) | P1(r2) + P2(r2) + P3(r2))
   */
  @Test
  public void preProcessingInParallelIsFaster()
    throws Exception {
    final StopWatch watch = new StopWatch();
    WroConfiguration config = Context.get().getConfig();

    initExecutor(createSlowPreProcessor(100), createSlowPreProcessor(100), createSlowPreProcessor(100));
    final List<Resource> resources = createResources(Resource.create("r1", ResourceType.JS),
      Resource.create("r2", ResourceType.JS));

    //warm up
    config.setParallelPreprocessing(true);
    executor.processAndMerge(resources, true);
    
    //parallel
    watch.start("parallel preProcessing");
    config.setParallelPreprocessing(true);
    executor.processAndMerge(resources, true);
    watch.stop();
    long parallelExecution = watch.getLastTaskTimeMillis();
    
    //sequential
    config.setParallelPreprocessing(false);
    watch.start("sequential preProcessing");
    executor.processAndMerge(resources, true);
    watch.stop();
    long sequentialExecution = watch.getLastTaskTimeMillis();

    String message = "Processing details: \n" + watch.prettyPrint();
    LOG.debug(message);

    // prove that running in parallel is faster
    // delta indicates the improvement relative to parallel execution (we use 80% relative improvement, but it normally
    // should be about 100%).
    double delta = parallelExecution * 0.8;
    Assert.assertTrue(String.format("%s  > %s + %s", sequentialExecution, parallelExecution, delta),
        sequentialExecution > parallelExecution + delta);
  }

  @Test
  public void shouldNotMinimizeDecoratedResourcesWithMinimizationDisabled()
    throws Exception {
    final List<Resource> resources = new ArrayList<Resource>();
    final Resource resource = Resource.create("classpath:1.js");
    resource.setMinimize(false);
    resources.add(resource);
    final ResourcePreProcessor preProcessor = CopyrightKeeperProcessorDecorator.decorate(new JSMinProcessor() {
      @Override
      public void process(final Resource resource, final Reader reader, final Writer writer)
          throws IOException {
        Assert.fail("Should not minimize");
      }
    });
    initExecutor(preProcessor);
    executor.processAndMerge(resources, true);
  }

  /**
   * When an empty resource is processed, the processing should not fail (warn only).
   */
  @Test
  public void shouldNotFailWhenEmptyResourceIsFound() throws Exception {
    final WroConfiguration config = Context.get().getConfig();
    config.setIgnoreMissingResources(false);
    
    final UriLocator emptyStreamLocator = new UriLocator() {
      public boolean accept(final String uri) {
        return true;
      }
      public InputStream locate(final String uri)
          throws IOException {
        return new ByteArrayInputStream("".getBytes());
      }
    };
    final UriLocatorFactory locatorFactory = new SimpleUriLocatorFactory().addUriLocator(emptyStreamLocator);
    //init executor
    WroManagerFactory managerFactory = new BaseWroManagerFactory().setUriLocatorFactory(locatorFactory);
    InjectorBuilder.create(managerFactory).build().inject(executor);
    
    final List<Resource> resources = new ArrayList<Resource>();
    resources.add(Resource.create("/resource.js"));
    executor.processAndMerge(resources, true);
  }
  
  @After
  public void tearDown() {
    Context.unset();
  }
}
