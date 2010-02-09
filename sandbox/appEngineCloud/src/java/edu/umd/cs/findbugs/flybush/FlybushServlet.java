package edu.umd.cs.findbugs.flybush;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.jdo.JDOObjectNotFoundException;
import javax.jdo.PersistenceManager;
import javax.jdo.Query;
import javax.jdo.Transaction;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.datanucleus.store.query.QueryResult;

import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.users.User;
import com.google.appengine.api.users.UserService;
import com.google.appengine.api.users.UserServiceFactory;
import com.google.apphosting.api.DeadlineExceededException;

import edu.umd.cs.findbugs.cloud.appEngine.protobuf.AppEngineProtoUtil;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.Evaluation;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.GetEvaluations;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.GetRecentEvaluations;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.Issue;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.LogIn;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.LogInResponse;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.RecentEvaluations;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.UploadEvaluation;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.UploadIssues;

@SuppressWarnings("serial")
public class FlybushServlet extends HttpServlet {

	private static final Logger LOGGER = Logger.getLogger(FlybushServlet.class.getName());

	private PersistenceManager persistenceManager;

	public FlybushServlet() {
		this(null);
	}

	/** for testing */
	FlybushServlet(PersistenceManager pm) {
		setPersistenceManager(pm);
	}

	/** for testing */
	void setPersistenceManager(PersistenceManager persistenceManager) {
		this.persistenceManager = persistenceManager;
	}

	public void doGet(HttpServletRequest req, HttpServletResponse resp)
			throws IOException {

		try {
			String uri = req.getPathInfo();
			PersistenceManager pm = getPersistenceManager();
			try {
				if (uri.startsWith("/browser-auth/")) {
					browserAuth(req, resp, pm);

				} else if (uri.startsWith("/check-auth/")) {
					checkAuth(req, resp, pm);

				} else {
					show404(resp);
				}
			} finally {
				pm.close();
			}
		} catch (DeadlineExceededException e) {
			LOGGER.log(Level.SEVERE, "Timed out", e);
		}
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp)
			throws IOException {
		String uri = req.getPathInfo();

		PersistenceManager pm = getPersistenceManager();
		try {
			if (uri.equals("/log-in")) {
				logIn(req, resp, pm);

			} else if (uri.startsWith("/log-out/")) {
				logOut(req, resp, pm);

			} else if (uri.equals("/upload-issues")) {
				uploadIssues(req, resp, pm);

			} else if (uri.equals("/upload-evaluation")) {
				uploadEvaluation(req, resp, pm);

			} else if (uri.equals("/get-evaluations")) {
				getEvaluations(req, resp, pm);

			} else if (uri.equals("/get-recent-evaluations")) {
				getRecentEvaluations(req, resp, pm);

			} else {
				show404(resp);
			}
		} finally {
			pm.close();
		}
	}

	private void show404(HttpServletResponse resp) throws IOException {
		setResponse(resp, 404, "Not Found");
	}

	private void setResponse(HttpServletResponse resp, int statusCode, String textResponse)
			throws IOException {
		resp.setStatus(statusCode);
		resp.setContentType("text/plain");
		resp.getWriter().println(textResponse);
	}

	private void browserAuth(HttpServletRequest req, HttpServletResponse resp,
			PersistenceManager pm) throws IOException {
		UserService userService = UserServiceFactory.getUserService();
		User user = userService.getCurrentUser();

		if (user != null) {
			long id = Long.parseLong(req.getPathInfo().substring("/browser-auth/".length()));
		    Date date = new Date();
		    SqlCloudSession session = new SqlCloudSession(user, id, date);

		    Transaction tx = pm.currentTransaction();
		    tx.begin();
		    try {
				pm.makePersistent(session);
				tx.commit();
			} finally {
				if (tx.isActive()) tx.rollback();
			}
			resp.setStatus(200);
		    resp.setContentType("text/html");
		    PrintWriter writer = resp.getWriter();
		    writer.println("<title>FindBugs Cloud</title>");
			writer.println("<h1>You are now signed in</h1>");
		    writer.println("<p style='font-size: large; font-weight: bold'>"
		    		+ "Please return to the FindBugs application window to continue.</p>");
		    writer.println("<p style='font-style: italic'>Signed in as " + user.getNickname()
		    		       + " (" + user.getEmail() + ")</p>");

		} else {
		    resp.sendRedirect(userService.createLoginURL(req.getPathInfo()));
		}
	}

	private void checkAuth(HttpServletRequest req, HttpServletResponse resp,
			PersistenceManager pm) throws IOException {
		long id = Long.parseLong(req.getPathInfo().substring("/check-auth/".length()));
		SqlCloudSession sqlCloudSession = lookupCloudSessionById(id, pm);
		if (sqlCloudSession == null) {
			setResponse(resp, 418, "FAIL");
		} else {
			setResponse(resp, 200,
					"OK\n"
					+ sqlCloudSession.getRandomID() + "\n"
					+ sqlCloudSession.getUser().getNickname());
		}
		resp.flushBuffer();
	}

	private void logOut(HttpServletRequest req, HttpServletResponse resp,
			PersistenceManager pm) throws IOException {
		long id = Long.parseLong(req.getPathInfo().substring("/log-out/".length()));
		Query query = pm.newQuery(
				"select from " + SqlCloudSession.class.getName() +
				" where randomID == " + id + "");
		Transaction tx = pm.currentTransaction();
		tx.begin();
		long deleted = 0;
		try {
			deleted = query.deletePersistentAll();
			query.execute();
			tx.commit();
		} finally {
			if (tx.isActive()) tx.rollback();
		}
		if (deleted == 1) {
			resp.setStatus(200);
		} else {
			setResponse(resp, 404, "no such session");
		}
	}

	private void logIn(HttpServletRequest req, HttpServletResponse resp,
			PersistenceManager pm) throws IOException {
		LogIn loginMsg = LogIn.parseFrom(req.getInputStream());
		SqlCloudSession session = lookupCloudSessionById(loginMsg.getSessionId(), pm);
		if (session == null) {
			setResponse(resp, 403, "not authenticated");
			return;
		}

		DbInvocation invocation = new DbInvocation();
		invocation.setWho(session.getUser().getNickname());
		invocation.setStartTime(loginMsg.getAnalysisTimestamp());

		Transaction tx = pm.currentTransaction();
		tx.begin();
		try {
			invocation = pm.makePersistent(invocation);
			Key invocationKey = invocation.getKey();
			session.setInvocation(invocationKey);
			pm.makePersistent(session);
			tx.commit();
		} finally {
			if (tx.isActive()) {
				tx.rollback();
			}
		}

		LogInResponse.Builder issueProtos = LogInResponse.newBuilder();
		List<String> decodedHashes = AppEngineProtoUtil.decodeHashes(loginMsg.getMyIssueHashesList());
		System.out.println("Looking up " + decodedHashes);
		for (DbIssue issue : lookupIssues(decodedHashes, pm)) {
			Issue issueProto = buildIssueProto(issue, issue.getEvaluations());
			System.out.println("Found issue " + AppEngineProtoUtil.decodeHash(issueProto.getHash()) + " - " + issueProto.getBugPattern());
			issueProtos.addFoundIssues(issueProto);
		}
		System.out.println();
		resp.setStatus(200);
		issueProtos.build().writeTo(resp.getOutputStream());
	}

	private void uploadIssues(HttpServletRequest req, HttpServletResponse resp,
			PersistenceManager pm) throws IOException {
		UploadIssues issues = UploadIssues.parseFrom(req.getInputStream());
		SqlCloudSession session = lookupCloudSessionById(issues.getSessionId(), pm);
		if (session == null) {
			resp.setStatus(403);
			return;
		}
		List<String> hashes = new ArrayList<String>();
		for (Issue issue : issues.getNewIssuesList()) {
			hashes.add(AppEngineProtoUtil.decodeHash(issue.getHash()));
		}
		HashSet<String> existingIssueHashes = lookupHashes(hashes, pm);
		for (Issue issue : issues.getNewIssuesList()) {
			if (!existingIssueHashes.contains(AppEngineProtoUtil.decodeHash(issue.getHash()))) {
				DbIssue dbIssue = new DbIssue();
				dbIssue.setHash(AppEngineProtoUtil.decodeHash(issue.getHash()));
				dbIssue.setBugPattern(issue.getBugPattern());
				dbIssue.setPriority(issue.getPriority());
				dbIssue.setPrimaryClass(issue.getPrimaryClass());
				dbIssue.setFirstSeen(issue.getFirstSeen());
				dbIssue.setLastSeen(issue.getFirstSeen()); // ignore last seen

				Transaction tx = pm.currentTransaction();
				tx.begin();
				try {
					pm.makePersistent(dbIssue);
					tx.commit();
				} finally {
					if (tx.isActive()) tx.rollback();
				}
			}
		}

		setResponse(resp, 200, "");
	}

	private void uploadEvaluation(HttpServletRequest req,
			HttpServletResponse resp, PersistenceManager pm) throws IOException {
		UploadEvaluation uploadEvalMsg = UploadEvaluation.parseFrom(req.getInputStream());
		SqlCloudSession session = lookupCloudSessionById(uploadEvalMsg.getSessionId(), pm);
		if (session == null) {
			setResponse(resp, 403, "not authenticated");
			return;
		}

		DbEvaluation dbEvaluation = createDbEvaluation(uploadEvalMsg.getEvaluation());
		dbEvaluation.setWho(session.getUser().getNickname());
		Key invocationKey = session.getInvocation();
		if (invocationKey != null) {
			DbInvocation invocation;
			try {
				invocation = (DbInvocation) pm.getObjectById(DbInvocation.class, invocationKey);
			if (invocation != null) {
				dbEvaluation.setInvocation(invocation.getKey());
			}
			} catch (JDOObjectNotFoundException e) {
				// ignore
			}
		}
		Transaction tx = pm.currentTransaction();
		boolean setStatusAlready = false;
		try {
		    tx.begin();

			String hash = AppEngineProtoUtil.decodeHash(uploadEvalMsg.getHash());
			DbIssue issue = findIssue(pm, hash);
			if (issue == null) {
				setResponse(resp, 404, "no such issue " + AppEngineProtoUtil.decodeHash(uploadEvalMsg.getHash()));
				setStatusAlready  = true;
				return;
			}
			dbEvaluation.setIssue(issue);
			issue.addEvaluation(dbEvaluation);
			pm.makePersistent(issue);

		    tx.commit();

		} finally {
		    if (tx.isActive()) {
		    	tx.rollback();
		    	if (!setStatusAlready) {
		    		setResponse(resp, 403, "Transaction failed");
		    	}
		    }
		}

		resp.setStatus(200);
	}

	@SuppressWarnings("unchecked")
	private void getRecentEvaluations(HttpServletRequest req,
			HttpServletResponse resp, PersistenceManager pm) throws IOException {
		GetRecentEvaluations recentEvalsRequest = GetRecentEvaluations.parseFrom(req.getInputStream());
		SqlCloudSession sqlCloudSession = lookupCloudSessionById(recentEvalsRequest.getSessionId(), pm);
		if (sqlCloudSession == null) {
			setResponse(resp, 403, "not authenticated");
			return;
		}
		long startTime = recentEvalsRequest.getTimestamp();
		Query query = pm.newQuery(
				"select from " + DbEvaluation.class.getName()
				+ " where when > " + startTime + " order by when"
				);
		SortedSet<DbEvaluation> evaluations = new TreeSet((List<DbEvaluation>) query.execute());
		RecentEvaluations.Builder issueProtos = RecentEvaluations.newBuilder();
		Map<String, SortedSet<DbEvaluation>> issues = groupUniqueEvaluationsByIssue(evaluations);
		for (SortedSet<DbEvaluation> evaluationsForIssue : issues.values()) {
			DbIssue issue = evaluations.iterator().next().getIssue();
			Issue issueProto = buildIssueProto(issue, evaluationsForIssue);
			issueProtos.addIssues(issueProto);
		}
		query.closeAll();

		resp.setStatus(200);
		issueProtos.build().writeTo(resp.getOutputStream());
	}

	private void getEvaluations(HttpServletRequest req,
			HttpServletResponse resp, PersistenceManager pm) throws IOException {
		GetEvaluations evalsRequest = GetEvaluations.parseFrom(req.getInputStream());
		SqlCloudSession sqlCloudSession = lookupCloudSessionById(evalsRequest.getSessionId(), pm);
		if (sqlCloudSession == null) {
			setResponse(resp, 403, "not authenticated");
			return;
		}

		RecentEvaluations.Builder response = RecentEvaluations.newBuilder();
		for (DbIssue issue : lookupIssues(AppEngineProtoUtil.decodeHashes(evalsRequest.getHashesList()), pm)) {
			Issue issueProto = buildIssueProto(issue, issue.getEvaluations());
			response.addIssues(issueProto);
		}

		resp.setStatus(200);
		ServletOutputStream output = resp.getOutputStream();
		response.build().writeTo(output);
		output.close();
	}

	private DbEvaluation createDbEvaluation(Evaluation protoEvaluation) {
		DbEvaluation dbEvaluation = new DbEvaluation();
		dbEvaluation.setComment(protoEvaluation.getComment());
		dbEvaluation.setDesignation(protoEvaluation.getDesignation());
		dbEvaluation.setWhen(protoEvaluation.getWhen());
		return dbEvaluation;
	}

	@SuppressWarnings("unchecked")
	private SqlCloudSession lookupCloudSessionById(long id, PersistenceManager pm) {
		Query query = pm.newQuery(
					"select from " + SqlCloudSession.class.getName() +
					" where randomID == " + id + " order by date desc range 0,1");
		try {
			List<SqlCloudSession> sessions = (List<SqlCloudSession>) query.execute();
			return sessions.isEmpty() ? null : sessions.get(0);
		} finally {
			query.closeAll();
		}
	}

	@SuppressWarnings("unchecked")
	private DbIssue findIssue(PersistenceManager pm, String hash) {
		Query query = pm.newQuery(DbIssue.class, "hash == :hashParam");
		try {
			Iterator<DbIssue> it = ((QueryResult) query.execute(hash)).iterator();
			if (!it.hasNext()) {
				return null;
			}
			return it.next();
		} finally {
			query.closeAll();
		}
	}

	private Issue buildIssueProto(DbIssue issue, SortedSet<DbEvaluation> evaluationsOrig2) {
		Issue.Builder issueBuilder = Issue.newBuilder()
				.setBugPattern(issue.getBugPattern())
				.setPriority(issue.getPriority())
				.setHash(AppEngineProtoUtil.encodeHash(issue.getHash()))
				.setFirstSeen(issue.getFirstSeen())
				.setLastSeen(issue.getLastSeen())
				.setPrimaryClass(issue.getPrimaryClass());
		LinkedList<DbEvaluation> evaluations = new LinkedList<DbEvaluation>();
		Set<String> seenUsernames = new HashSet<String>();
		List<DbEvaluation> evaluationsOrig = new ArrayList<DbEvaluation>(evaluationsOrig2);
		ListIterator<DbEvaluation> it = evaluationsOrig.listIterator(evaluationsOrig.size());
		while (it.hasPrevious()) {
			DbEvaluation dbEvaluation = it.previous();
			boolean userIsNew = seenUsernames.add(dbEvaluation.getWho());
			if (userIsNew) {
				evaluations.add(0, dbEvaluation);
			}
		}
		for (DbEvaluation dbEval : evaluations) {
			issueBuilder.addEvaluations(Evaluation.newBuilder()
					.setComment(dbEval.getComment())
					.setDesignation(dbEval.getDesignation())
					.setWhen(dbEval.getWhen())
					.setWho(dbEval.getWho()).build());
		}
		return issueBuilder.build();
	}

	private Map<String, SortedSet<DbEvaluation>> groupUniqueEvaluationsByIssue(SortedSet<DbEvaluation> evaluations) {
		Map<String,SortedSet<DbEvaluation>> issues = new HashMap<String, SortedSet<DbEvaluation>>();
		for (DbEvaluation dbEvaluation : evaluations) {
			String issueHash = dbEvaluation.getIssue().getHash();
			SortedSet<DbEvaluation> evaluationsForIssue = issues.get(issueHash);
			if (evaluationsForIssue == null) {
				evaluationsForIssue = new TreeSet<DbEvaluation>();
				issues.put(issueHash, evaluationsForIssue);
			}
			// only include the latest evaluation for each user
			for (Iterator<DbEvaluation> it = evaluationsForIssue.iterator(); it.hasNext();) {
				DbEvaluation eval = it.next();
				if (eval.getWho().equals(dbEvaluation.getWho()))
					it.remove();
			}
			evaluationsForIssue.add(dbEvaluation);
		}
		return issues;
	}

	@SuppressWarnings("unchecked")
	private List<DbIssue> lookupIssues(Iterable<String> hashes, PersistenceManager pm) {
		Query query = pm.newQuery("select from " + DbIssue.class.getName() + " where :hashes.contains(hash)");
		List<DbIssue> result = (List<DbIssue>) query.execute(hashes);
		return result;
	}

	@SuppressWarnings("unchecked")
	private HashSet<String> lookupHashes(Iterable<String> hashes, PersistenceManager pm) {
		Query query = pm.newQuery("select from " + DbIssue.class.getName()
				+ " where :hashes.contains(hash)");
		query.setResult("hash");
		List<String> result = (List<String>) query.execute(hashes);
		query.closeAll();
		return new HashSet<String>(result);
	}

	private PersistenceManager getPersistenceManager() {
		if (persistenceManager != null) {
			return persistenceManager;
		}

		return PMF.get().getPersistenceManager();

	}
}