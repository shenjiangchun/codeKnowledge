#!/usr/bin/env python
"""Write all 8 fixengine test files."""
import os, sys

BASE = r'src/test/java/com/huawei/hisi/fixengine'
os.makedirs(BASE + '/executor', exist_ok=True)
os.makedirs(BASE + '/service', exist_ok=True)

files = {}

files[BASE + '/executor/TestRunResultTest.java'] = open(os.path.join(os.path.dirname(__file__), 'templates/TestRunResultTest.java'), 'r').read() if os.path.exists(os.path.join(os.path.dirname(__file__), 'templates/TestRunResultTest.java')) else None

# Define all files inline
files = {}

files[BASE + '/executor/TestRunResultTest.java'] = '''package com.huawei.hisi.fixengine.executor;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
public class TestRunResultTest {
    @Test void passTrue() { assertThat(new TestRunResult(0,"x").isPassed()).isTrue(); }
    @Test void passFalse1() { assertThat(new TestRunResult(1,"x").isPassed()).isFalse(); }
    @Test void passFalseMinus() { assertThat(new TestRunResult(-1,"x").isPassed()).isFalse(); }
    @Test void reprodFailure() { assertThat(new TestRunResult(1,"FAILURE!\\nNullPointerException:x\\n").isReproduced("NullPointerException","x")).isTrue(); }
    @Test void reprodError() { assertThat(new TestRunResult(1,"ERROR!\\nIllegalStateException:x\\nBUILD FAILURE\\n").isReproduced("IllegalStateException",null)).isTrue(); }
    @Test void reprodMissing() { assertThat(new TestRunResult(1,"FAILURE!\\n").isReproduced("NPE",null)).isFalse(); }
    @Test void reprodNullOut() { assertThat(new TestRunResult(1,null).isReproduced("NPE",null)).isFalse(); }
    @Test void reprodNullType() { assertThat(new TestRunResult(1,"FAILURE!").isReproduced(null,"x")).isFalse(); }
    @Test void reprodMsgMismatch() { assertThat(new TestRunResult(1,"FAILURE!\\nNPE:got\\n").isReproduced("NPE","expected")).isFalse(); }
    @Test void reprodBlankMsg() { assertThat(new TestRunResult(1,"FAILURE!\\nRuntimeException\\n").isReproduced("RuntimeException"," ")).isTrue(); }
    @Test void reprodFallback() { assertThat(new TestRunResult(1,"mvn...\\nNPE:boom\\n").isReproduced("NPE","boom")).isTrue(); }
    @Test void reprodEmpty() { assertThat(new TestRunResult(1,"").isReproduced("Ex",null)).isFalse(); }
    @Test void accessors() { var r=new TestRunResult(2,"out"); assertThat(r.exitCode()).isEqualTo(2); assertThat(r.output()).isEqualTo("out"); }
}
'''

files[BASE + '/executor/MavenExecutorTest.java'] = '''package com.huawei.hisi.fixengine.executor;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
public class MavenExecutorTest {
    @Test void nonExistDir() { var r=new MavenExecutor().runTest("/no/such/proj","com.foo.Test",null); assertThat(r.isPassed()).isFalse(); }
    @Test void nullModule() { var r=new MavenExecutor().runTest(System.getProperty("java.io.tmpdir"),"com.foo.Test",null); assertThat(r).isNotNull(); }
}
'''

files[BASE + '/executor/GitExecutorTest.java'] = '''package com.huawei.hisi.fixengine.executor;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
public class GitExecutorTest {
    @Test void revParseHeadNonGit() { assertThat(new GitExecutor().revParseHead(System.getProperty("java.io.tmpdir"))).isNull(); }
    @Test void currentBranchNonGit() { assertThat(new GitExecutor().currentBranch(System.getProperty("java.io.tmpdir"))).isNull(); }
}
'''

files[BASE + '/service/WorktreeServiceTest.java'] = '''package com.huawei.hisi.fixengine.service;
import com.huawei.hisi.fixengine.executor.GitExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
@ExtendWith(MockitoExtension.class)
public class WorktreeServiceTest {
    @Mock private GitExecutor git;
    @Test void cw() { var s=new WorktreeService(git,"/tmp/wt"); assertThat(s.createWorktree("fix/123","/repo","master")).isEqualTo("/tmp/wt/fix/123"); verify(git).createWorktree("/repo","/tmp/wt/fix/123","fix/123","master"); }
    @Test void cb() { when(git.currentBranch("/repo")).thenReturn("main"); assertThat(new WorktreeService(git,"/x").currentBranch("/repo")).isEqualTo("main"); }
    @Test void wtf() throws IOException { Path d=Files.createTempDirectory("wt"); try { new WorktreeService(git,"/x").writeTestFile(d.toString(),"com.foo","Test","class Test{}"); var f=d.resolve("src/test/java/com/foo/Test.java"); assertThat(f).exists(); assertThat(Files.readString(f)).isEqualTo("class Test{}"); } finally { deleteDir(d); } }
    @Test void wtfErr() throws IOException { Path d=Files.createTempDirectory("wt"); try { Files.writeString(d.resolve("src"),"block"); var s=new WorktreeService(git,"/x"); assertThatThrownBy(()->s.writeTestFile(d.toString(),"com.foo","T","c")).isInstanceOf(RuntimeException.class).hasMessageContaining("Failed to write test file"); } finally { deleteDir(d); } }
    @Test void af() throws IOException { Path d=Files.createTempDirectory("wt"); try { new WorktreeService(git,"/x").applyFix(d.toString(),"src/main/java/Foo.java","class Foo{}"); assertThat(d.resolve("src/main/java/Foo.java")).exists(); } finally { deleteDir(d); } }
    @Test void ch() { when(git.revParseHead("/wt")).thenReturn("abc123"); assertThat(new WorktreeService(git,"/x").commit("fix","/wt","fix:test")).isEqualTo("abc123"); verify(git).commitAll("/wt","fix:test"); verify(git).revParseHead("/wt"); }
    @Test void cn() { when(git.revParseHead("/wt")).thenReturn(null); assertThat(new WorktreeService(git,"/x").commit("fix","/wt","fix:test")).isNull(); }
    @Test void ct() { doThrow(new RuntimeException("git fail")).when(git).commitAll("/wt","fix:test"); assertThatThrownBy(()->new WorktreeService(git,"/x").commit("fix","/wt","fix:test")).isInstanceOf(RuntimeException.class).hasMessageContaining("git fail"); }
    private static void deleteDir(Path d) { try { Files.walk(d).sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception e){}}); } catch(Exception e) {} }
}
'''

files[BASE + '/service/FixFlowRunnerTest.java'] = open(os.path.join(os.path.dirname(__file__), 'templates/FixFlowRunnerTest.java'), 'r').read() if os.path.exists(os.path.join(os.path.dirname(__file__), 'templates/FixFlowRunnerTest.java')) else '''package com.huawei.hisi.fixengine.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.fixengine.executor.MavenExecutor;
import com.huawei.hisi.fixengine.executor.TestRunResult;
import com.huawei.hisi.fixengine.model.FixSession;
import com.huawei.hisi.fixengine.model.TestGenInput;
import com.huawei.hisi.fixengine.repository.FixSessionRepository;
import com.huawei.hisi.loganalysis.orchestrator.LogAnalysisDagOrchestrator;
import com.huawei.hisi.ram.chat.RamChatWebSocketHandler;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.repository.LogAnalysisRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
@ExtendWith(MockitoExtension.class)
public class FixFlowRunnerTest {
    @Mock private LogAnalysisDagOrchestrator logOrch;
    @Mock private WorktreeService wtSvc;
    @Mock private TestGenService tgSvc;
    @Mock private ReproService repSvc;
    @Mock private FixService fixSvc;
    @Mock private MavenExecutor mvnExec;
    @Mock private FixSessionRepository fixRepo;
    @Mock private RamChatWebSocketHandler ws;
    @Mock private LogAnalysisRepository logRepo;
    @Mock private AgentEventRepository evtRepo;
    private final ObjectMapper om = new ObjectMapper();
    private FixFlowRunner runner;
    @org.junit.jupiter.api.BeforeEach void setUp() { runner = new FixFlowRunner(logOrch,wtSvc,tgSvc,repSvc,fixSvc,mvnExec,fixRepo,ws,logRepo,evtRepo,om); when(evtRepo.append(any())).thenAnswer(inv->inv.getArgument(0)); when(fixRepo.update(any())).thenReturn(1); }
    @AfterEach void tearDown() { System.clearProperty("hisi.fix.repo-path"); }
    @Test void cname() throws Exception { var m=FixFlowRunner.class.getDeclaredMethod("extractTestClassName",TestGenInput.class); m.setAccessible(true); assertThat((String)m.invoke(null,TestGenInput.builder().testMethodSignature("com.foo.Bar.do").build())).isEqualTo("ReproTest"); }
    @Test void cpackage() throws Exception { var m=FixFlowRunner.class.getDeclaredMethod("extractTestPackage",TestGenInput.class); m.setAccessible(true); assertThat((String)m.invoke(null,TestGenInput.builder().testMethodSignature("com.foo.Bar.do").build())).isEqualTo("com.foo"); }
    @Test void cpackageNull() throws Exception { var m=FixFlowRunner.class.getDeclaredMethod("extractTestPackage",TestGenInput.class); m.setAccessible(true); assertThat((String)m.invoke(null,TestGenInput.builder().build())).isEqualTo("com.huawei.hisi.fixengine.test"); }
    @Test void flatExtracts() throws Exception { var m=FixFlowRunner.class.getDeclaredMethod("flattenDagOutputs",Map.class); m.setAccessible(true); var d=new LinkedHashMap<String,Object>(); d.put("parsedError",Map.of("errorType","NullPointerException")); d.put("keyFrames",List.of(Map.of("fullSignature","com.foo.Bar.do"))); m.invoke(runner,d); assertThat(d).containsEntry("exceptionType","NullPointerException"); }
    @Test void flatNoOverwrite() throws Exception { var m=FixFlowRunner.class.getDeclaredMethod("flattenDagOutputs",Map.class); m.setAccessible(true); var d=new LinkedHashMap<String,Object>(); d.put("exceptionType","o"); d.put("parsedError",Map.of("errorType","X")); m.invoke(runner,d); assertThat(d.get("exceptionType")).isEqualTo("o"); }
    @Test void flatSkipUnknown() throws Exception { var m=FixFlowRunner.class.getDeclaredMethod("flattenDagOutputs",Map.class); m.setAccessible(true); var d=new LinkedHashMap<String,Object>(); d.put("parsedError",Map.of("errorType","Unknown")); m.invoke(runner,d); assertThat(d).doesNotContainKey("exceptionType"); }
    @Test void flatEmptyKF() throws Exception { var m=FixFlowRunner.class.getDeclaredMethod("flattenDagOutputs",Map.class); m.setAccessible(true); var d=new LinkedHashMap<String,Object>(); d.put("keyFrames",List.of()); m.invoke(runner,d); assertThat(d).doesNotContainKey("throwPointSig"); }
    @Test void flatNoSig() throws Exception { var m=FixFlowRunner.class.getDeclaredMethod("flattenDagOutputs",Map.class); m.setAccessible(true); var d=new LinkedHashMap<String,Object>(); d.put("keyFrames",List.of(Map.of("className","C"))); m.invoke(runner,d); assertThat(d).doesNotContainKey("throwPointSig"); }
    @Test void happy() { var s=ses("100","42","fix/100-a"); sh(s); runner.run(s); ArgumentCaptor<FixSession> c=ArgumentCaptor.forClass(FixSession.class); verify(fixRepo,atLeastOnce()).update(c.capture()); assertThat(c.getValue().getStatus()).isEqualTo("SUCCESS"); }
    @Test void checkpoint() { var s=ses("101","43","fix/101-a"); sh(s); runner.run(s); ArgumentCaptor<AgentEvent> c=ArgumentCaptor.forClass(AgentEvent.class); verify(evtRepo,atLeastOnce()).append(c.capture()); assertThat(c.getAllValues().stream().anyMatch(e->e.getType()==com.huawei.hisi.ram.model.EventType.CHECKPOINT)).isTrue(); }
    @Test void reproFails() { var s=ses("200","44","fix/200-a"); sh(s); when(repSvc.runAndCheckRepro(anyString(),anyString(),anyString(),any(),any(Integer.class))).thenReturn(false); runner.run(s); ArgumentCaptor<FixSession> c=ArgumentCaptor.forClass(FixSession.class); verify(fixRepo,atLeastOnce()).update(c.capture()); assertThat(c.getValue().getStatus()).isEqualTo("SUCCESS_UNVERIFIED"); }
    @Test void passFails() { var s=ses("300","45","fix/300-a"); sh(s); when(repSvc.runAndCheckPass(anyString(),anyString())).thenReturn(false); runner.run(s); ArgumentCaptor<FixSession> c=ArgumentCaptor.forClass(FixSession.class); verify(fixRepo,atLeastOnce()).update(c.capture()); assertThat(c.getValue().getStatus()).isEqualTo("SUCCESS_UNVERIFIED"); }
    @Test void bothFail() { var s=ses("400","46","fix/400-a"); sh(s); when(repSvc.runAndCheckRepro(anyString(),anyString(),anyString(),any(),any(Integer.class))).thenReturn(false); when(repSvc.runAndCheckPass(anyString(),anyString())).thenReturn(false); runner.run(s); ArgumentCaptor<FixSession> c=ArgumentCaptor.forClass(FixSession.class); verify(fixRepo,atLeastOnce()).update(c.capture()); assertThat(c.getValue().getStatus()).isEqualTo("SUCCESS_UNVERIFIED"); }
    @Test void missSig() { var s=ses("500","47","fix/500-a"); s.setThrowPointSig(null); sr("500"); when(logOrch.analyzeLog(anyString(),any(),anyString(),any(),any())).thenReturn(null); System.setProperty("hisi.fix.repo-path","/repo"); when(wtSvc.currentBranch("/repo")).thenReturn("master"); when(wtSvc.createWorktree(anyString(),anyString(),anyString())).thenReturn("/tmp/wt500"); runner.run(s); ArgumentCaptor<FixSession> c=ArgumentCaptor.forClass(FixSession.class); verify(fixRepo,atLeastOnce()).update(c.capture()); assertThat(c.getValue().getStatus()).isEqualTo("MISSING_SIGNATURE"); }
    @Test void step1Log() { var s=ses("600","48","fix/600-a"); sh(s); runner.run(s); verify(logOrch).analyzeLog(anyString(),any(),anyString(),any(),any()); }
    @Test void step3Wt() { var s=ses("700","49","fix/700-a"); sh(s); runner.run(s); verify(wtSvc).createWorktree(anyString(),anyString(),anyString()); }
    @Test void step8Commit() { var s=ses("800","50","fix/800-a"); sh(s); runner.run(s); verify(wtSvc).commit(anyString(),anyString(),anyString()); }
    @Test void chatZero() { var s=ses("900","0","fix/900-a"); sh(s); runner.run(s); ArgumentCaptor<FixSession> c=ArgumentCaptor.forClass(FixSession.class); verify(fixRepo,atLeastOnce()).update(c.capture()); assertThat(c.getValue().getStatus()).isEqualTo("SUCCESS"); }
    @Test void nonNumChat() { var s=ses("901","abc","fix/901-a"); sh(s); runner.run(s); ArgumentCaptor<FixSession> c=ArgumentCaptor.forClass(FixSession.class); verify(fixRepo,atLeastOnce()).update(c.capture()); assertThat(c.getValue().getStatus()).isEqualTo("SUCCESS"); }
    @Test void exceptionTest() { var s=ses("999","51","fix/999-e"); s.setErrorMsg("err"); sr("999"); when(logOrch.analyzeLog(anyString(),any(),any(),any(),any())).thenThrow(new RuntimeException("crash")); runner.run(s); ArgumentCaptor<FixSession> c=ArgumentCaptor.forClass(FixSession.class); verify(fixRepo,atLeastOnce()).update(c.capture()); assertThat(c.getValue().getStatus()).isEqualTo("ERROR"); }
    private FixSession ses(String rid,String cid,String bn) { return FixSession.builder().id("fix-"+rid).reportId(rid).chatSessionId(cid).status("RUNNING").branchName(bn).errorMsg("NPE").throwPointSig("com.foo.Bar.do").tenantId("default").createBy("system").delFlag(0).build(); }
    private void sr(String rid) { var r=new LogAnalysisRepository.LogAnalysisReportEntity(); r.setId(100L); var q=new LinkedHashMap<String,Object>(); q.put("projectPath","/repo"); r.setQueryParams(q); r.setLogStackTrace("at com.foo.Bar.do(Bar.java:10)"); when(logRepo.findById(Long.parseLong(rid))).thenReturn(r); }
    @SuppressWarnings("unchecked")
    private void sh(FixSession s) { sr(s.getReportId()); var d=new LinkedHashMap<String,Object>(); d.put("parsedError",Map.of("errorType","NullPointerException")); d.put("keyFrames",List.of(Map.of("fullSignature","com.foo.Bar.do"))); d.put("exceptionMessage","b"); d.put("projectPath","/repo"); try{when(logOrch.analyzeLog(anyString(),any(),anyString(),any(),any())).thenReturn(d);}catch(Exception e){throw new RuntimeException(e);} when(wtSvc.currentBranch(anyString())).thenReturn("master"); when(wtSvc.createWorktree(anyString(),anyString(),anyString())).thenReturn("/tmp/wt/"+s.getBranchName()); when(tgSvc.generate(any(TestGenInput.class),any())).thenAnswer(inv->{var cc=(java.util.function.Function<String,String>)inv.getArgument(1);cc.apply("c");return"c";}); when(mvnExec.runTest(anyString(),anyString(),any())).thenReturn(new TestRunResult(0,"OK")); when(repSvc.runAndCheckRepro(anyString(),anyString(),anyString(),any(),any(Integer.class))).thenReturn(true); when(fixSvc.fix(anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn("class F{}"); when(repSvc.runAndCheckPass(anyString(),anyString())).thenReturn(true); when(wtSvc.commit(anyString(),anyString(),anyString())).thenReturn("abc123"); }
}
'''

for path, content in files.items():
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)
    print(f'Wrote {os.path.basename(path)} ({len(content)} bytes)')

print(f'Total: {len(files)} files written')
