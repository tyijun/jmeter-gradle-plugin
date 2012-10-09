package org.veil.gradle.plugins.jmeter;

import org.apache.commons.io.IOUtils;
import org.apache.jmeter.JMeter;
import org.apache.tools.ant.DirectoryScanner;
import org.gradle.api.GradleException;

import javax.xml.transform.TransformerException;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.security.Permission;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class JmeterRunTask extends JmeterAbstractTask {

    /**
     * Path to a Jmeter test XML file.
     * Relative to srcDir.
     * May be declared instead of the parameter includes.
     */
    private List<File> jmeterTestFiles;

    /**
     * Sets the list of include patterns to use in directory scan for JMeter Test XML files.
     * Relative to srcDir.
     * May be declared instead of a single jmeterTestFile.
     * Ignored if parameter jmeterTestFile is given.
     */
    private List<String> includes;

    /**
     * Sets the list of exclude patterns to use in directory scan for Test files.
     * Relative to srcDir.
     * Ignored if parameter jmeterTestFile file is given.
     */
    private List<String> excludes;

    /**
     * Directory in which the reports are stored.
     * <p/>
     * By default build/jmeter-report"
     */
    private File reportDir;

    /**
     * Whether or not to generate reports after measurement.
     * <p/>
     * By default true
     */
    private Boolean enableReports = null;

    /**
     * Use remote JMeter installation to run tests
     * <p/>
     * By default false
     */
    private Boolean remote = null;

    /**
     * Sets whether ErrorScanner should ignore failures in JMeter result file.
     * <p/>
     * By default false
     */
    private Boolean jmeterIgnoreFailure = null;

    /**
     * Sets whether ErrorScanner should ignore errors in JMeter result file.
     * <p/>
     * By default false
     */
    private Boolean jmeterIgnoreError = null;

    /**
     * Postfix to add to report file.
     * <p/>
     * By default "-report.html"
     */
    private String reportPostfix;

     /**
     * Custom Xslt which is used to create the report.
     */
    private File reportXslt;

    private DateFormat fmt = new SimpleDateFormat("yyyyMMdd");

    @Override
    protected void runTaskAction() throws IOException {
        List<String> testFiles = new ArrayList<String>();
        if (jmeterTestFiles != null) {
            for (File f : jmeterTestFiles) {
                if (f.exists() && f.isFile()) {
                    testFiles.add(f.getCanonicalPath());
                } else {
                    throw new GradleException("Test file " + f.getCanonicalPath() + " does not exists");
                }
            }
        } else {
            testFiles.addAll(scanSourceFolder());
        }

        List<String> results = new ArrayList<String>();
        for (String file : testFiles) {
            results.add(executeJmeterTest(file));
        }

        if (this.enableReports) {
            makeReport(results);
        }
        checkForErrors(results);

    }


    @Override
    protected void loadPropertiesFromConvention() {
        super.loadPropertiesFromConvention();
        jmeterIgnoreError = getJmeterIgnoreError();
        jmeterIgnoreFailure = getJmeterIgnoreFailure();
        jmeterTestFiles = getJmeterTestFiles();
        reportDir = getReportDir();
        remote = getRemote();
        enableReports = getEnableReports();
        reportPostfix = getReportPostfix();
        reportXslt = getReportXslt();
        includes = getIncludes();
        excludes = getExcludes();
    }

     private void checkForErrors(List<String> results) {
        ErrorScanner scanner = new ErrorScanner(this.jmeterIgnoreError, this.jmeterIgnoreFailure);
        try {
            for (String file : results) {
                if (scanner.scanForProblems(new File(file))) {
                    log.warn("There were test errors.  See the jmeter logs for details");
                }
            }
        } catch (IOException e) {
            throw new GradleException("Can't read log file", e);
        }
    }

    private void makeReport(List<String> results) {
        try {
            ReportTransformer transformer;
            transformer = new ReportTransformer(getXslt());
            log.info("Building JMeter Report.");
            for (String resultFile : results) {
                final String outputFile = toOutputFileName(resultFile);
                log.info("transforming: " + resultFile + " to " + outputFile);
                transformer.transform(resultFile, outputFile);
            }
        } catch (FileNotFoundException e) {
            throw new GradleException("Error writing report file jmeter file.", e);
        } catch (TransformerException e) {
            throw new GradleException("Error transforming jmeter results", e);
        } catch (IOException e) {
            throw new GradleException("Error copying resources to jmeter results", e);
        }
    }

    private InputStream getXslt() throws IOException {
        if (this.reportXslt == null) {
            //if we are using the default report, also copy the images out.
            IOUtils.copy(Thread.currentThread().getContextClassLoader().getResourceAsStream("reports/collapse.jpg"), new FileOutputStream(this.reportDir.getPath() + File.separator + "collapse.jpg"));
            IOUtils.copy(Thread.currentThread().getContextClassLoader().getResourceAsStream("reports/expand.jpg"), new FileOutputStream(this.reportDir.getPath() + File.separator + "expand.jpg"));
            return Thread.currentThread().getContextClassLoader().getResourceAsStream("reports/jmeter-results-detail-report_21.xsl");
        } else {
            return new FileInputStream(this.reportXslt);
        }
    }

     private String toOutputFileName(String fileName) {
        if (fileName.endsWith(".xml")) {
            return fileName.replace(".xml", this.reportPostfix);
        } else {
            return fileName + this.reportPostfix;
        }
    }

    private String executeJmeterTest(String fileLocation) {

        SecurityManager oldManager = System.getSecurityManager();
        Thread.UncaughtExceptionHandler oldHandler = Thread.getDefaultUncaughtExceptionHandler();

        try {
            File testFile = new File(fileLocation);
            File resultFile = new File(reportDir, testFile.getName() + "-" + fmt.format(new Date()) + ".xml");
            resultFile.delete();
            List<String> args = new ArrayList<String>();
             args.addAll(Arrays.asList("-n",
                     "-t", testFile.getCanonicalPath(),
                     "-l", resultFile.getCanonicalPath(),
                     "-d", getProject().getProjectDir().getCanonicalPath(),
                     "-p", getSrcDir().getAbsolutePath() + File.separator + JMETER_DEFAULT_PROPERTY_NAME));

            initUserProperties(args);

            if (remote) {
                args.add("-r");
            }
            log.debug("JMeter is called with the following command line arguments: " + args.toString());


            setCustomSecurityManager();

            setCustomUncaughtExceptionHandler();

            try {
                JMeter jmeter = new JMeter();
                jmeter.start(args.toArray(new String[]{}));
                BufferedReader in = new BufferedReader(new FileReader(getJmeterLog()));
                while (!checkForEndOfTest(in)) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            } catch (ExitException ee) {
                if (ee.getCode() != 0) {
                    throw new GradleException("Test failed", ee);
                }
            } finally {
                System.setSecurityManager(oldManager);
                Thread.setDefaultUncaughtExceptionHandler(oldHandler);
            }
            return resultFile.getCanonicalPath();


        } catch (IOException e) {
            throw new GradleException("Can't execute test", e);
        }
    }


    private boolean checkForEndOfTest(BufferedReader in) {
        boolean testEnded = false;
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.contains("Test has ended")) {
                    testEnded = true;
                    break;
                } else if (line.contains("Could not open")) {
                    testEnded = true;
                    break;
                }
            }
        } catch (IOException e) {
            throw new GradleException("Can't read log file", e);
        }
        return testEnded;
    }

    private List<String> scanSourceFolder() {
        List<String> result = new ArrayList<String>();
        DirectoryScanner scaner = new DirectoryScanner();
        scaner.setBasedir(getSrcDir());
        scaner.setIncludes(includes == null ? new String[]{"**/*.jmx"} : includes.toArray(new String[]{}));
        if (excludes != null) {
            scaner.setExcludes(excludes.toArray(new String[]{}));
        }
        scaner.scan();
        for (String localPath : scaner.getIncludedFiles()) {
            result.add(scaner.getBasedir() + File.separator + localPath);
        }
        return result;
    }

    public List<File> getJmeterTestFiles() {
        return jmeterTestFiles;
    }

    public void setJmeterTestFiles(List<File> jmeterTestFiles) {
        this.jmeterTestFiles = jmeterTestFiles;
    }

    public List<String> getIncludes() {
        return includes;
    }

    public void setIncludes(List<String> includes) {
        this.includes = includes;
    }

    public List<String> getExcludes() {
        return excludes;
    }

    public void setExcludes(List<String> excludes) {
        this.excludes = excludes;
    }

    public File getReportDir() {
        return reportDir;
    }

    public void setReportDir(File reportDir) {
        this.reportDir = reportDir;
    }

    public Boolean getEnableReports() {
        return enableReports;
    }

    public void setEnableReports(Boolean enableReports) {
        this.enableReports = enableReports;
    }

    public Boolean getRemote() {
        return remote;
    }

    public void setRemote(Boolean remote) {
        this.remote = remote;
    }

    public Boolean getJmeterIgnoreFailure() {
        return jmeterIgnoreFailure;
    }

    public void setJmeterIgnoreFailure(Boolean jmeterIgnoreFailure) {
        this.jmeterIgnoreFailure = jmeterIgnoreFailure;
    }

    public Boolean getJmeterIgnoreError() {
        return jmeterIgnoreError;
    }

    public void setJmeterIgnoreError(Boolean jmeterIgnoreError) {
        this.jmeterIgnoreError = jmeterIgnoreError;
    }

    public String getReportPostfix() {
        return reportPostfix;
    }

    public void setReportPostfix(String reportPostfix) {
        this.reportPostfix = reportPostfix;
    }

    public File getReportXslt() {
        return reportXslt;
    }

    public void setReportXslt(File reportXslt) {
        this.reportXslt = reportXslt;
    }
}
