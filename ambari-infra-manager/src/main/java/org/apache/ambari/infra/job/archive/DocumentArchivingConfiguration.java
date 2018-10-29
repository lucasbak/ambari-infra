/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ambari.infra.job.archive;

import static org.apache.ambari.infra.job.JobsPropertyMap.PARAMETERS_CONTEXT_KEY;
import static org.apache.ambari.infra.job.archive.SolrQueryBuilder.computeEnd;
import static org.apache.commons.lang.StringUtils.isBlank;

import java.io.File;

import javax.inject.Inject;

import org.apache.ambari.infra.conf.InfraManagerDataConfig;
import org.apache.ambari.infra.conf.security.PasswordStore;
import org.apache.ambari.infra.job.AbstractJobsConfiguration;
import org.apache.ambari.infra.job.JobContextRepository;
import org.apache.ambari.infra.job.JobScheduler;
import org.apache.ambari.infra.job.ObjectSource;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.configuration.support.JobRegistryBeanPostProcessor;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DocumentArchivingConfiguration extends AbstractJobsConfiguration<DocumentArchivingProperties, ArchivingParameters> {
  private static final Logger LOG = LoggerFactory.getLogger(DocumentArchivingConfiguration.class);
  private static final DocumentWiper NOT_DELETE = (firstDocument, lastDocument) -> { };

  private final StepBuilderFactory steps;
  private final Step exportStep;

  @Inject
  public DocumentArchivingConfiguration(
          DocumentArchivingPropertyMap jobsPropertyMap,
          JobScheduler scheduler,
          StepBuilderFactory steps,
          JobBuilderFactory jobs,
          @Qualifier("exportStep") Step exportStep,
          JobRegistryBeanPostProcessor jobRegistryBeanPostProcessor) {
    super(jobsPropertyMap.getSolrDataArchiving(), scheduler, jobs, jobRegistryBeanPostProcessor);
    this.exportStep = exportStep;
    this.steps = steps;
  }

  @Override
  protected Job buildJob(JobBuilder jobBuilder) {
    return jobBuilder.start(exportStep).build();
  }

  @Bean
  @JobScope
  public Step exportStep(DocumentExporter documentExporter) {
    return steps.get("export")
            .tasklet(documentExporter)
            .build();
  }

  @Bean
  @StepScope
  public DocumentExporter documentExporter(DocumentItemReader documentItemReader,
                                           @Value("#{stepExecution.jobExecution.jobId}") String jobId,
                                           @Value("#{stepExecution.jobExecution.executionContext.get('" + PARAMETERS_CONTEXT_KEY + "')}") ArchivingParameters parameters,
                                           InfraManagerDataConfig infraManagerDataConfig,
                                           @Value("#{jobParameters[end]}") String intervalEnd,
                                           DocumentWiper documentWiper,
                                           JobContextRepository jobContextRepository,
                                           PasswordStore passwordStore) {

    File baseDir = new File(infraManagerDataConfig.getDataFolder(), "exporting");
    CompositeFileAction fileAction = new CompositeFileAction(new BZip2Compressor());
    switch (parameters.getDestination()) {
      case S3:
        fileAction.add(new S3Uploader(
                parameters.s3Properties().orElseThrow(() -> new IllegalStateException("S3 properties are not provided!")),
                passwordStore));
        break;
      case HDFS:
        org.apache.hadoop.conf.Configuration conf = new org.apache.hadoop.conf.Configuration();
        conf.set("fs.defaultFS", parameters.getHdfsEndpoint());
        fileAction.add(new HdfsUploader(conf, new Path(parameters.getHdfsDestinationDirectory()), parameters.getHdfsFilePermission()));
        break;
      case LOCAL:
        baseDir = new File(parameters.getLocalDestinationDirectory());
        break;
    }

    FileNameSuffixFormatter fileNameSuffixFormatter = FileNameSuffixFormatter.from(parameters);
    LocalItemWriterListener itemWriterListener = new LocalItemWriterListener(fileAction, documentWiper);
    File destinationDirectory = new File(
            baseDir,
            String.format("%s_%s_%s",
                    parameters.getSolr().getCollection(),
                    jobId,
                    isBlank(intervalEnd) ? "" : fileNameSuffixFormatter.format(intervalEnd)));
    LOG.info("Destination directory path={}", destinationDirectory);
    if (!destinationDirectory.exists()) {
      if (!destinationDirectory.mkdirs()) {
        LOG.warn("Unable to create directory {}", destinationDirectory);
      }
    }

    return new DocumentExporter(
            documentItemReader,
            firstDocument -> new LocalDocumentItemWriter(
                    outFile(parameters.getSolr().getCollection(), destinationDirectory, fileNameSuffixFormatter.format(firstDocument)), itemWriterListener),
            parameters.getWriteBlockSize(), jobContextRepository);
  }

  @Bean
  @StepScope
  public DocumentWiper documentWiper(@Value("#{stepExecution.jobExecution.executionContext.get('" + PARAMETERS_CONTEXT_KEY + "')}") ArchivingParameters parameters,
                                     SolrDAO solrDAO) {
    if (isBlank(parameters.getSolr().getDeleteQueryText()))
      return NOT_DELETE;
    return solrDAO;
  }

  @Bean
  @StepScope
  public SolrDAO solrDAO(@Value("#{stepExecution.jobExecution.executionContext.get('" + PARAMETERS_CONTEXT_KEY + "')}") ArchivingParameters parameters) {
    return new SolrDAO(parameters.getSolr());
  }

  private File outFile(String collection, File directoryPath, String suffix) {
    File file = new File(directoryPath, String.format("%s_-_%s.json", collection, suffix));
    LOG.info("Exporting to temp file {}", file.getAbsolutePath());
    return file;
  }

  @Bean
  @StepScope
  public DocumentItemReader reader(ObjectSource<Document> documentSource,
                                   @Value("#{stepExecution.jobExecution.executionContext.get('" + PARAMETERS_CONTEXT_KEY + "')}") ArchivingParameters properties) {
    return new DocumentItemReader(documentSource, properties.getReadBlockSize());
  }

  @Bean
  @StepScope
  public ObjectSource<Document> documentSource(@Value("#{stepExecution.jobExecution.executionContext.get('" + PARAMETERS_CONTEXT_KEY + "')}") ArchivingParameters parameters,
                                               SolrDAO solrDAO) {

    return new SolrDocumentSource(solrDAO, parameters.getStart(), computeEnd(parameters.getEnd(), parameters.getTtl()));
  }
}
