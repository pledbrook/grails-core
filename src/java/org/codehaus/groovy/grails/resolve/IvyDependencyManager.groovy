/* Copyright 2004-2005 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.groovy.grails.resolve

import org.apache.ivy.core.event.EventManager
import org.apache.ivy.core.module.descriptor.Configuration
import org.apache.ivy.core.module.descriptor.DefaultModuleDescriptor
import org.apache.ivy.core.module.descriptor.DependencyDescriptor
import org.apache.ivy.core.module.id.ModuleRevisionId
import org.apache.ivy.core.report.ResolveReport
import org.apache.ivy.core.resolve.IvyNode
import org.apache.ivy.core.resolve.ResolveEngine
import org.apache.ivy.core.resolve.ResolveOptions
import org.apache.ivy.core.settings.IvySettings
import org.apache.ivy.core.sort.SortEngine
import org.apache.ivy.plugins.resolver.ChainResolver
import org.apache.ivy.plugins.resolver.DependencyResolver
import org.apache.ivy.plugins.resolver.FileSystemResolver
import org.apache.ivy.plugins.resolver.IBiblioResolver
import org.apache.ivy.util.DefaultMessageLogger
import org.apache.ivy.util.Message

import grails.util.BuildSettings
import org.apache.ivy.core.module.descriptor.ExcludeRule
import grails.util.GrailsNameUtils

import org.apache.ivy.core.module.id.ModuleId
import org.apache.ivy.core.report.ArtifactDownloadReport
import org.apache.ivy.core.module.descriptor.ModuleDescriptor
import org.apache.ivy.core.module.descriptor.DefaultDependencyArtifactDescriptor
import grails.util.Metadata
import groovyx.gpars.Parallelizer
import org.codehaus.groovy.tools.RootLoader

import org.apache.ivy.util.MessageLogger
import org.apache.ivy.core.module.descriptor.Artifact
import org.apache.ivy.core.report.ConfigurationResolveReport
import org.apache.ivy.core.report.DownloadReport
import org.apache.ivy.core.report.DownloadStatus
import org.apache.ivy.core.module.descriptor.DefaultExcludeRule
import org.apache.ivy.core.module.id.ArtifactId
import org.apache.ivy.core.module.descriptor.DefaultDependencyDescriptor
import org.apache.ivy.plugins.repository.TransferListener
import java.util.concurrent.ConcurrentLinkedQueue

import jsr166y.forkjoin.ForkJoinPool;

import org.apache.ivy.plugins.resolver.RepositoryResolver
import org.apache.ivy.plugins.parser.m2.PomModuleDescriptorParser
import org.apache.ivy.core.resolve.ResolvedModuleRevision
import org.apache.ivy.core.resolve.ResolveData
import org.apache.ivy.plugins.resolver.util.ResolvedResource
import org.apache.ivy.core.resolve.DownloadOptions
import org.apache.ivy.core.cache.ArtifactOrigin
import org.apache.ivy.core.search.OrganisationEntry
import org.apache.ivy.core.search.ModuleEntry
import org.apache.ivy.core.search.RevisionEntry
import org.apache.ivy.plugins.namespace.Namespace
import org.apache.ivy.plugins.resolver.ResolverSettings
import org.apache.ivy.core.cache.RepositoryCacheManager

/**
 * Implementation that uses Apache Ivy under the hood.
 *
 * @author Graeme Rocher
 * @since 1.2
 */
@Typed(TypePolicy.MIXED)
class IvyDependencyManager extends AbstractIvyDependencyManager implements DependencyResolver, DependencyDefinitionParser{

    
    ResolveEngine resolveEngine
    BuildSettings buildSettings
    IvySettings ivySettings
    MessageLogger logger
    Metadata metadata
    ChainResolver chainResolver = new ChainResolver(name:"default",returnFirst:true)  
    DefaultDependencyDescriptor currentDependencyDescriptor
    Collection repositoryData = new ConcurrentLinkedQueue()
    Collection<String> configuredPlugins = new ConcurrentLinkedQueue()
    
    Collection moduleExcludes = new ConcurrentLinkedQueue()
    TransferListener transferListener

    boolean readPom = false
    boolean inheritsAll = false
    boolean resolveErrors = false
	boolean defaultDependenciesProvided = false
	boolean pluginsOnly = false
	boolean inheritRepositories = true
	
	private static ForkJoinPool forkJoinPool
    /**
     * Creates a new IvyDependencyManager instance
     */
    IvyDependencyManager(String applicationName, String applicationVersion, BuildSettings settings=null, Metadata metadata = null) {
        ivySettings = new IvySettings()

        ivySettings.defaultInit()
        // don't cache for snapshots
        if (settings?.grailsVersion?.endsWith("SNAPSHOT")) {
            ivySettings.setDefaultUseOrigin(true)
        }

        ivySettings.validate = false
        chainResolver.settings = ivySettings
        def eventManager = new EventManager()
        def sortEngine = new SortEngine(ivySettings)
        resolveEngine = new ResolveEngine(ivySettings,eventManager,sortEngine)
        resolveEngine.dictatorResolver = chainResolver

        this.applicationName = applicationName
        this.applicationVersion = applicationVersion
        this.buildSettings = settings
        this.metadata = metadata
    }

    /**
     * Allows settings an alternative chain resolver to be used
     * @param resolver The resolver to be used
     */
    void setChainResolver(ChainResolver resolver) {
        this.chainResolver = resolver
        resolveEngine.dictatorResolver = chainResolver
    }

    /**
     * Sets the default message logger used by Ivy
     *
     * @param logger
     */
    void setLogger(MessageLogger logger) {
        Message.setDefaultLogger logger
        this.logger = logger
    }

    MessageLogger getLogger() { this.logger }

    /**
     * @return The current chain resolver
     */
    ChainResolver getChainResolver() { chainResolver }

    /**
     * Resets the Grails plugin resolver if it is used
     */
    void resetGrailsPluginsResolver() {
        def resolver = chainResolver.resolvers.find { DependencyResolver r -> r.name == 'grailsPlugins' }
        chainResolver.resolvers.remove(resolver)
        chainResolver.resolvers.add(new GrailsPluginsDirectoryResolver(buildSettings, ivySettings))
    }


    /**
     * Serializes the parsed dependencies using the given builder.
     *
     * @param builder A builder such as groovy.xml.MarkupBuilder
     */
    void serialize(builder, boolean createRoot = true) {
        if (createRoot) {
            builder.dependencies {
                serializeResolvers(builder)
                serializeDependencies(builder)
            }
        }
        else {
            serializeResolvers(builder)
            serializeDependencies(builder)
        }
    }

    private serializeResolvers(builder) {
        builder.resolvers {
            for (resolverData in repositoryData) {
                if (resolverData.name=='grailsHome') continue
                builder.resolver resolverData
            }
        }
    }

    private serializeDependencies(builder) {
        for (EnhancedDefaultDependencyDescriptor dd in dependencyDescriptors) {
            // dependencies inherited by Grails' global config are not included
            if (dd.inherited) continue

            def mrid = dd.dependencyRevisionId
            builder.dependency( group: mrid.organisation, name: mrid.name, version: mrid.revision, conf: dd.scope, transitive: dd.transitive) {
                for (ExcludeRule er in dd.allExcludeRules) {
                    def mid = er.id.moduleId
                    excludes group:mid.organisation,name:mid.name
                }
            }
        }
    }

    /**
     * Obtains the default dependency definitions for the given Grails version
     */
    static Closure getDefaultDependencies(String grailsVersion) {
        return {
            // log level of Ivy resolver, either 'error', 'warn', 'info', 'debug' or 'verbose'
            log "warn"
            repositories {
                grailsPlugins()
                grailsHome()
                // uncomment the below to enable remote dependency resolution
                // from public Maven repositories
                //mavenCentral()
                //mavenLocal()
                //mavenRepo "http://snapshots.repository.codehaus.org"
                //mavenRepo "http://repository.codehaus.org"
                //mavenRepo "http://download.java.net/maven/2/"
                //mavenRepo "http://repository.jboss.com/maven2/
            }
            dependencies {
				def compileTimeDependenciesMethod = defaultDependenciesProvided ? 'provided' : 'compile'
				def runtimeDependenciesMethod = defaultDependenciesProvided ? 'provided' : 'runtime'
				def parsingClosure = { ModuleRevisionId.newInstance(*it.split(/:/)) }
				
				Parallelizer.withExistingParallelizer(forkJoinPool) {
					// dependencies needed by the Grails build system
					[ ModuleRevisionId.newInstance("org.tmatesoft.svnkit", "svnkit", "1.3.1"),
					  ModuleRevisionId.newInstance("org.apache.ant","ant","1.7.1"),
					  ModuleRevisionId.newInstance("org.apache.ant","ant-launcher","1.7.1"),
					  ModuleRevisionId.newInstance("org.apache.ant","ant-junit","1.7.1"),
					  ModuleRevisionId.newInstance("org.apache.ant","ant-nodeps","1.7.1"),
					  ModuleRevisionId.newInstance("org.apache.ant","ant-trax","1.7.1"),
					  ModuleRevisionId.newInstance("jline","jline","0.9.94"),
					  ModuleRevisionId.newInstance("org.fusesource.jansi","jansi","1.2.1"),
					  ModuleRevisionId.newInstance("xalan","serializer","2.7.1"),
					  ModuleRevisionId.newInstance("org.grails","grails-docs",grailsVersion),
					  ModuleRevisionId.newInstance("org.grails","grails-bootstrap", grailsVersion),
					  ModuleRevisionId.newInstance("org.grails","grails-scripts",grailsVersion),
					  ModuleRevisionId.newInstance("org.grails","grails-core",grailsVersion),
					  ModuleRevisionId.newInstance("org.grails","grails-resources",grailsVersion),
					  ModuleRevisionId.newInstance("org.grails","grails-web",grailsVersion),
					  ModuleRevisionId.newInstance("org.slf4j","slf4j-api","1.5.8"),
					  ModuleRevisionId.newInstance("org.slf4j","slf4j-log4j12","1.5.8"),
					  ModuleRevisionId.newInstance("org.springframework","org.springframework.test","3.0.3.RELEASE"),
					  ModuleRevisionId.newInstance("com.googlecode.concurrentlinkedhashmap","concurrentlinkedhashmap-lru","1.0_jdk5")].eachParallel { mrid ->
							def dependencyDescriptor = new EnhancedDefaultDependencyDescriptor(mrid, false, false ,"build")
							  addDependency mrid
							configureDependencyDescriptor(dependencyDescriptor, "build", null, false)
					  }
	
					
					["org.xhtmlrenderer:core-renderer:R8",
					 "com.lowagie:itext:2.0.8",
					 "radeox:radeox:1.0-b2"].collect(parsingClosure).eachParallel { mrid ->
					   def dependencyDescriptor = new EnhancedDefaultDependencyDescriptor(mrid, false, false ,"docs")
					   addDependency mrid
					   configureDependencyDescriptor(dependencyDescriptor, "docs", null, false)
					}
	
					// dependencies needed during development, but not for deployment
					["javax.servlet:servlet-api:2.5",
					  "javax.servlet:jsp-api:2.1"].collect(parsingClosure).eachParallel {mrid ->
					   def dependencyDescriptor = new EnhancedDefaultDependencyDescriptor(mrid, false, false ,"provided")
					   addDependency mrid
					   configureDependencyDescriptor(dependencyDescriptor, "provided", null, false)
					}
	
					// dependencies needed for compilation
					"${compileTimeDependenciesMethod}"("org.codehaus.groovy:groovy-all:1.7.5") {
						excludes 'jline'
					}
	
					"${compileTimeDependenciesMethod}"("commons-beanutils:commons-beanutils:1.8.0", "commons-el:commons-el:1.0", "commons-validator:commons-validator:1.3.1") {
						excludes "commons-logging", "xml-apis"
					}
	
					[	"org.coconut.forkjoin:jsr166y:070108",
						"org.codehaus.gpars:gpars:0.9",
						"aopalliance:aopalliance:1.0",
						"com.googlecode.concurrentlinkedhashmap:concurrentlinkedhashmap-lru:1.0_jdk5",
						"commons-codec:commons-codec:1.4",
						"commons-collections:commons-collections:3.2.1",
						"commons-io:commons-io:1.4",
						"commons-lang:commons-lang:2.4",
						"javax.transaction:jta:1.1",
						"org.hibernate:ejb3-persistence:1.0.2.GA",
						"opensymphony:sitemesh:2.4",
						"org.grails:grails-bootstrap:$grailsVersion",
						"org.grails:grails-core:$grailsVersion",
						"org.grails:grails-crud:$grailsVersion",
						"org.grails:grails-gorm:$grailsVersion",
						"org.grails:grails-resources:$grailsVersion",
						"org.grails:grails-spring:$grailsVersion",
						"org.grails:grails-web:$grailsVersion",
						"org.springframework:org.springframework.core:3.0.3.RELEASE",
						"org.springframework:org.springframework.aop:3.0.3.RELEASE",
						"org.springframework:org.springframework.aspects:3.0.3.RELEASE",
						"org.springframework:org.springframework.asm:3.0.3.RELEASE",
						"org.springframework:org.springframework.beans:3.0.3.RELEASE",
						"org.springframework:org.springframework.context:3.0.3.RELEASE",
						"org.springframework:org.springframework.context.support:3.0.3.RELEASE",
						"org.springframework:org.springframework.expression:3.0.3.RELEASE",
						"org.springframework:org.springframework.instrument:3.0.3.RELEASE",
						"org.springframework:org.springframework.jdbc:3.0.3.RELEASE",
						"org.springframework:org.springframework.jms:3.0.3.RELEASE",
						"org.springframework:org.springframework.orm:3.0.3.RELEASE",
						"org.springframework:org.springframework.oxm:3.0.3.RELEASE",
						"org.springframework:org.springframework.transaction:3.0.3.RELEASE",
						"org.springframework:org.springframework.web:3.0.3.RELEASE",
						"org.springframework:org.springframework.web.servlet:3.0.3.RELEASE",
						"org.slf4j:slf4j-api:1.5.8"].collect(parsingClosure).eachParallel {mrid ->
							   def dependencyDescriptor = new EnhancedDefaultDependencyDescriptor(mrid, false, false ,compileTimeDependenciesMethod)
							   addDependency mrid
							   configureDependencyDescriptor(dependencyDescriptor, compileTimeDependenciesMethod, null, false)
						}
	
	
						// dependencies needed for running tests
						["junit:junit:4.8.1",
						 "org.grails:grails-test:$grailsVersion",
						 "org.springframework:org.springframework.test:3.0.3.RELEASE"].collect(parsingClosure).eachParallel {mrid ->
							   def dependencyDescriptor = new EnhancedDefaultDependencyDescriptor(mrid, false, false ,"test")
							   addDependency mrid
							   configureDependencyDescriptor(dependencyDescriptor, "test", null, false)
						}
	
						// dependencies needed at runtime only
						[ 	"org.aspectj:aspectjweaver:1.6.8",
							"org.aspectj:aspectjrt:1.6.8",
							"cglib:cglib-nodep:2.1_3",
							"commons-fileupload:commons-fileupload:1.2.1",
							"oro:oro:2.0.8",
							"javax.servlet:jstl:1.1.2",
							// data source
							"commons-dbcp:commons-dbcp:1.3",
							"commons-pool:commons-pool:1.5.5",
							"hsqldb:hsqldb:1.8.0.10",
							"com.h2database:h2:1.2.144",
							// JSP support
							"apache-taglibs:standard:1.1.2",
							"xpp3:xpp3_min:1.1.3.4.O" ].collect(parsingClosure).eachParallel {mrid ->
							   def dependencyDescriptor = new EnhancedDefaultDependencyDescriptor(mrid, false, false ,runtimeDependenciesMethod)
							   addDependency mrid
							   configureDependencyDescriptor(dependencyDescriptor, runtimeDependenciesMethod, null, false)
						}
	
						// caching
						"${runtimeDependenciesMethod}" ("net.sf.ehcache:ehcache-core:1.7.1") {
							excludes 'jms', 'commons-logging', 'servlet-api'
						}
	
						// logging
						"${runtimeDependenciesMethod}"("log4j:log4j:1.2.16",
								"org.slf4j:jcl-over-slf4j:1.5.8",
								"org.slf4j:jul-to-slf4j:1.5.8",
								"org.slf4j:slf4j-log4j12:1.5.8" ) {
							excludes 'mail', 'jms', 'jmxtools', 'jmxri'
						}
				}
				

            }
        }
    }

    /**
     * Returns all of the dependency descriptors for dependencies of the application and not
     * those inherited from frameworks or plugins
     */
    Set<DependencyDescriptor> getApplicationDependencyDescriptors(String scope = null) {
        dependencyDescriptors.findAll { EnhancedDefaultDependencyDescriptor dd ->
            !dd.inherited && (!scope || dd.scope == scope)
        }
    }

    /**
    * Returns all the dependency descriptors for dependencies of a plugin that have been exported for use in the application
    */
    Set<DependencyDescriptor> getExportedDependencyDescriptors(String scope = null) {
        getApplicationDependencyDescriptors(scope).findAll { ((EnhancedDefaultDependencyDescriptor) it).exported }
    }

    boolean isExcluded(String name) {
        def aid = createExcludeArtifactId(name)
        return moduleDescriptor.doesExclude(configurationNames, aid)
    }

    /**
     * For usages such as addPluginDependency("foo", [group:"junit", name:"junit", version:"4.8.1"])
     *
     * This method is designed to be used by the internal framework and plugins and not be end users.
     * The idea is that plugins can provide dependencies at runtime which are then inherited by
     * the user's dependency configuration
     *
     * A user can however override a plugin's dependencies inside the dependency resolution DSL
     */
    void addPluginDependency(String pluginName, Map args) {
        // do nothing if the dependencies of the plugin are configured by the application
        if (isPluginConfiguredByApplication(pluginName)) return
        if (args?.group && args?.name && args?.version) {
            def transitive = getBooleanValue(args, 'transitive')
            def exported = getBooleanValue(args, 'export')
            String scope = args.conf ?: 'runtime'
            def mrid = ModuleRevisionId.newInstance(args.group, args.name, args.version)
            def dd = new EnhancedDefaultDependencyDescriptor(mrid, true, transitive, (String) scope)
            dd.exported = exported
            dd.inherited = true
            dd.plugin = pluginName
            configureDependencyDescriptor(dd, scope)
            if (args.excludes && args.excludes instanceof List) {
                for (String ex in ((List) args.excludes)) {
                    dd.exclude(ex)
                }
            }
            addDependencyDescriptor dd
        }
    }

    boolean isPluginConfiguredByApplication(String name) {
        (configuredPlugins.contains(name) || configuredPlugins.contains(GrailsNameUtils.getPropertyNameForLowerCaseHyphenSeparatedName(name)))
    }

    Set<ModuleRevisionId> getModuleRevisionIds(String org) { orgToDepMap[org] }

    /**
     * Lists all known dependencies for the given configuration name (defaults to all dependencies)
     */
    IvyNode[] listDependencies(String conf = null) {
        def options = new ResolveOptions()
        if (conf) {
            options.confs = [conf] as String[]
        }

        resolveEngine.getDependencies(moduleDescriptor, options, new ResolveReport(moduleDescriptor))
    }

    ResolveReport resolveDependencies(Configuration conf) {
        resolveDependencies(conf.name)
    }

    /**
     * Performs a resolve of all dependencies for the given configuration,
     * potentially going out to the internet to download jars if they are not found locally
     */
    ResolveReport resolveDependencies(String conf) {
        resolveErrors = false
        if (usedConfigurations.contains(conf) || conf == '') {
            def options = new ResolveOptions(checkIfChanged:false, outputReport:true, validate:false)
            if (conf) {
                options.confs = [conf] as String[]
            }

            ResolveReport resolve = resolveEngine.resolve(moduleDescriptor, options)
            resolveErrors = resolve.hasError()
            return resolve
        }

        // return an empty resolve report
        return new ResolveReport(moduleDescriptor)
    }

    /**
     * Similar to resolveDependencies, but will load the resolved dependencies into the
     * application RootLoader if it exists
     *
     * @return The ResolveReport
     * @throws IllegalStateException If no RootLoader exists
     */
    ResolveReport loadDependencies(String conf = '') {

        RootLoader rootLoader = getClass().classLoader.rootLoader
        if (rootLoader) {
            def urls = rootLoader.URLs.toList()
            ResolveReport report = resolveDependencies(conf)
            for (ArtifactDownloadReport downloadReport in report.allArtifactsReports) {
                def url = downloadReport.localFile.toURL()
                if (!urls.contains(url)) {
                    rootLoader.addURL(url)
                }
            }
        }
        else {
            throw new IllegalStateException("No root loader found. Could not load dependencies. Note this method cannot be called when running in a WAR.")
        }
    }

    /**
     * Resolves only application dependencies and returns a list of the resolves JAR files
     */
    List<ArtifactDownloadReport> resolveApplicationDependencies(String conf = '') {
        ResolveReport report = resolveDependencies(conf)

        def descriptors = getApplicationDependencyDescriptors(conf)
        report.allArtifactsReports.findAll { ArtifactDownloadReport downloadReport ->
            def mrid = downloadReport.artifact.moduleRevisionId
            descriptors.any { DependencyDescriptor dd -> mrid == dd.dependencyRevisionId}
        }
    }

    /**
     * Resolves only plugin dependencies that should be exported to the application
     */
    List<ArtifactDownloadReport> resolveExportedDependencies(String conf='') {

        def descriptors = getExportedDependencyDescriptors(conf)
        resolveApplicationDependencies(conf)?.findAll { ArtifactDownloadReport downloadReport ->
            def mrid = downloadReport.artifact.moduleRevisionId
            descriptors.any { DependencyDescriptor dd -> mrid == dd.dependencyRevisionId}
        }
    }

    /**
     * Performs a resolve of all dependencies, potentially going out to the internet to download jars
     * if they are not found locally
     */
    ResolveReport resolveDependencies() {
        resolveDependencies('')
    }

    /**
     * Performs a resolve of declared plugin dependencies (zip files containing plugin distributions)
     */
    ResolveReport resolvePluginDependencies(String conf = '', Map args = [:]) {
        resolveErrors = false
        if (usedConfigurations.contains(conf) || conf == '') {

//            if (args.checkIfChanged == null) args.checkIfChanged = true
//            if (args.outputReport == null) args.outputReport = true
//            if (args.validate == null) args.validate = false

            def options = new ResolveOptions()
            options.checkIfChanged = args.checkIfChanged ?: true
            options.outputReport = args.outputReport ?: true
            options.validate = args.validate ?: false
            if (args.date) options.date = args.date
            if (args.resolveId) options.resolveId = args.resolveId
            if (args.resolveMode) options.resolveMode = args.resolveMode
            if (args.revision) options.revision = args.revision
            if (args.download != null) options.download = args.download
            if (args.refresh != null) options.refresh = args.refresh
            if (args.useCacheOnly != null) options.useCacheOnly = args.useCacheOnly

            if (conf) {
                options.confs = [conf] as String[]
            }

            def md = createModuleDescriptor()
            for (dd in pluginDependencyDescriptors) {
                md.addDependency dd
            }
            if (!options.download) {
                def date = new Date()
                def report = new ResolveReport(md)
                def ivyNodes = resolveEngine.getDependencies(md, options, report)
                for (IvyNode node in ivyNodes) {
                    if (node.isLoaded()) {
                        for (Artifact a in node.allArtifacts) {
                            def origin = resolveEngine.locate(a)
                            def cr = new ConfigurationResolveReport(resolveEngine, md, conf, date, options)
                            def dr = new DownloadReport()
                            def adr = new ArtifactDownloadReport(a)
                            adr.artifactOrigin = origin
                            adr.downloadStatus = DownloadStatus.NO
                            dr.addArtifactReport(adr)
                            cr.addDependency(node, dr)
                            report.addReport(conf, cr)
                        }
                    }
                }
                return report
            }

            ResolveReport resolve = resolveEngine.resolve(md, options)
            resolveErrors = resolve.hasError()
            return resolve
        }

        // return an empty resolve report
        return new ResolveReport(createModuleDescriptor())
    }

    /**
     * Tests whether the given ModuleId is defined in the list of dependencies
     */
    boolean hasDependency(ModuleId mid) {
        return modules.contains(mid)
    }

    /**
     * Tests whether the given group and name are defined in the list of dependencies
     */
    boolean hasDependency(String group, String name) {
        return hasDependency(ModuleId.newInstance(group, name))
    }

    /**
     * Parses the Ivy DSL definition
     */
    void parseDependencies(Closure definition) {
		this.forkJoinPool = new ForkJoinPool()
		try {
			if (definition && applicationName && applicationVersion) {
				if (this.moduleDescriptor == null) {
					this.moduleDescriptor = createModuleDescriptor()
				}
	
				def evaluator = new IvyDomainSpecificLanguageEvaluator(this)
				definition.delegate = evaluator
				definition.resolveStrategy = Closure.DELEGATE_FIRST
				definition()
				evaluator = null
	
				if (readPom && buildSettings) {
					List dependencies = readDependenciesFromPOM()
	
					if (dependencies != null) {
						for (DependencyDescriptor dependencyDescriptor in dependencies) {
							ModuleRevisionId moduleRevisionId = dependencyDescriptor.getDependencyRevisionId()
							ModuleId moduleId = moduleRevisionId.getModuleId()
	
							String groupId = moduleRevisionId.getOrganisation()
							String artifactId = moduleRevisionId.getName()
							String version = moduleRevisionId.getRevision()
							String scope = Arrays.asList(dependencyDescriptor.getModuleConfigurations()).get(0)
	
							if (!hasDependency(moduleId)) {
								def enhancedDependencyDescriptor = new EnhancedDefaultDependencyDescriptor(moduleRevisionId, false, true, scope)
								for (ExcludeRule excludeRule in dependencyDescriptor.getAllExcludeRules()) {
									ModuleId excludedModule = excludeRule.getId().getModuleId()
									enhancedDependencyDescriptor.addRuleForModuleId(
											excludedModule,
											scope,
											EnhancedDefaultDependencyDescriptor.WILDCARD,
											EnhancedDefaultDependencyDescriptor.WILDCARD)
								}
								configureDependencyDescriptor(enhancedDependencyDescriptor, scope)
								addDependencyDescriptor enhancedDependencyDescriptor
							}
						}
					}
				}
	
				def installedPlugins = metadata?.installedPlugins
				if (installedPlugins) {
					for (entry in installedPlugins.entrySet()) {
						if (!pluginDependencyNames.contains(entry.key)) {
							def name = entry.key
							def scope = "runtime"
							def mrid = ModuleRevisionId.newInstance("org.grails.plugins", name, entry.value)
							def dd = new EnhancedDefaultDependencyDescriptor(mrid, true, true, scope)
							def artifact = new DefaultDependencyArtifactDescriptor(dd, name, "zip", "zip", null, null )
							dd.addDependencyArtifact(scope, artifact)
							metadataRegisteredPluginNames << name
							configureDependencyDescriptor(dd, scope, null, true)
							pluginDependencyDescriptors << dd
						}
					}
				}
			}
		}
		finally {
			forkJoinPool.shutdown()
		}

    }

    List readDependenciesFromPOM() {
      List fixedDependencies = null
      def pom = new File("${buildSettings.baseDir.path}/pom.xml")
      if (pom.exists()) {
          PomModuleDescriptorParser parser = PomModuleDescriptorParser.getInstance()
          ModuleDescriptor md = parser.parseDescriptor(ivySettings, pom.toURL(), false)

          fixedDependencies = md.dependencies as List<DependencyDescriptor>
      }

      return fixedDependencies
    }

    /**
     * Parses dependencies of a plugin
     *
     * @param pluginName the name of the plugin
     * @param definition the Ivy DSL definition
     */
    void parseDependencies(String pluginName,Closure definition) {
		this.forkJoinPool = new ForkJoinPool()
		try {
			if (definition) {
				if (moduleDescriptor == null) {
					throw new IllegalStateException("Call parseDependencies(Closure) first to parse the application dependencies")
				}
	
				def evaluator = new IvyDomainSpecificLanguageEvaluator(this)
				evaluator.currentPluginBeingConfigured = pluginName
				definition.delegate = evaluator
				definition.resolveStrategy = Closure.DELEGATE_FIRST
				definition()
			}
		}
		finally {
			forkJoinPool.shutdown()
		}

    }

    boolean getBooleanValue(Map dependency, String name) {
        return dependency.containsKey(name) ? Boolean.valueOf(dependency[name]) : true
    }

    String getName() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    void setName(String s) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    ResolvedModuleRevision getDependency(DependencyDescriptor dependencyDescriptor, ResolveData resolveData) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    ResolvedResource findIvyFileRef(DependencyDescriptor dependencyDescriptor, ResolveData resolveData) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    DownloadReport download(Artifact[] artifacts, DownloadOptions downloadOptions) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    ArtifactDownloadReport download(ArtifactOrigin artifactOrigin, DownloadOptions downloadOptions) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    boolean exists(Artifact artifact) {
        return false;  //To change body of implemented methods use File | Settings | File Templates.
    }

    ArtifactOrigin locate(Artifact artifact) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    void publish(Artifact artifact, File file, boolean b) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    void beginPublishTransaction(ModuleRevisionId moduleRevisionId, boolean b) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    void abortPublishTransaction() {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    void commitPublishTransaction() {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    void reportFailure() {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    void reportFailure(Artifact artifact) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    String[] listTokenValues(String s, Map map) {
        return new String[0];  //To change body of implemented methods use File | Settings | File Templates.
    }

    Map[] listTokenValues(String[] strings, Map map) {
        return new Map[0];  //To change body of implemented methods use File | Settings | File Templates.
    }

    OrganisationEntry[] listOrganisations() {
        return new OrganisationEntry[0];  //To change body of implemented methods use File | Settings | File Templates.
    }

    ModuleEntry[] listModules(OrganisationEntry organisationEntry) {
        return new ModuleEntry[0];  //To change body of implemented methods use File | Settings | File Templates.
    }

    RevisionEntry[] listRevisions(ModuleEntry moduleEntry) {
        return new RevisionEntry[0];  //To change body of implemented methods use File | Settings | File Templates.
    }

    Namespace getNamespace() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    void dumpSettings() {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    void setSettings(ResolverSettings resolverSettings) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    RepositoryCacheManager getRepositoryCacheManager() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }
}
