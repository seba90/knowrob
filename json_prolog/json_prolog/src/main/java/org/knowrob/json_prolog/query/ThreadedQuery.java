/* 
 * Copyright (c) 2014, Daniel Beßler
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of Willow Garage, Inc. nor the names of its
 *       contributors may be used to endorse or promote products derived from
 *       this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package org.knowrob.json_prolog.query;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Map;
import java.util.LinkedList;

import org.jpl7.Term;

/**
 * A prolog query that is processed in a separate thread.
 * Each query has a prolog engine assigned and can only be
 * processed exclusively by one thread.
 * Incremental queries require the ThreadedQuery in order to make
 * sure that the query is processed in one thread only.
 *
 * @author Daniel Beßler
 */
public class ThreadedQuery implements Runnable {

	public String getQueryString() {
		return queryString;
	}

	private String queryString = null;

	private org.jpl7.Term queryTerm = null;

	private org.jpl7.Query query = null;

	private boolean isStarted = false;

	private boolean isRunning = true;

	private boolean isClosed = true;

	private LinkedList<QueryCommand> commadQueue = new LinkedList<QueryCommand>();

	private QueryCommand currentCommand = null;

	private Exception exception = null;

	public ThreadedQuery(String queryString) {
		this.queryString = queryString;
	}

	public ThreadedQuery(org.jpl7.Term term) {
		this.queryTerm = term;
	}

	public Object getQueryObject() {
		return queryString!=null ? queryString : queryTerm;
	}

	public void waitOnThread() {
		  while(!isStarted()) {
				synchronized (getQueryObject()) {
					try {
						//In rare cases this line creates a dead lock.
						//Since the while loop checks if the thread is started, we can add a timeout to the wait
						//function, to avoid a dead lock.
						getQueryObject().wait(300000);
						//getQueryObject().wait();
						if(!isStarted){
							this.exception = new Exception("DEAD LOCK APPEARED " + queryString);
							isStarted = true;
						}
					}
					catch (Exception e) {

					}
				}
		  }
	}

	@Override
	public void run() {
		isStarted = true;
		isClosed = false;

		// Create a query (bound to this thread)
		try {
			if(queryString!=null) {
				query = new org.jpl7.Query(queryString);
			}
			else if(queryTerm!=null) {
				query = new org.jpl7.Query(queryTerm);
			}
			else {
				throw new RuntimeException("No query defined!");
			}
		}
		catch(Exception exc) {
			query = null;
			isClosed = true;
			isRunning = false;
			exception  = exc;
			// Wake up caller waiting on the thread to be started
			synchronized (getQueryObject()) {
				try { getQueryObject().notifyAll(); }
				catch (Exception e) {}
			}
			return;
		}
		// Wake up caller waiting on the thread to be started
		// (i.e., wake up caller of the method `waitOnThread`)
		synchronized (getQueryObject()) {
			try { getQueryObject().notifyAll(); }
			catch (Exception e) {}
		}
		// Start processing query commands
		QueryCommand cmd = null;
		try {
			while(isRunning) { // until thread is closed
				if(commadQueue.isEmpty()) {
					// Wait for command to be pushed onto the command queue
					synchronized (this) {
						try {
							System.out.println("WAITING DUE TO EMPTY QUEUE ...");
							this.wait();
							System.out.println("DONE WAITING DUE TO EMPTY QUEUE");
						}
						catch (Exception e) {}
					}
				}
				else {
					// poll command from queue
					synchronized (commadQueue) {
						cmd = commadQueue.poll();
					}
					currentCommand = cmd;
					// process the command
					System.out.println("EXECUTING COMMAND ..." + queryString);
					cmd.result = cmd.execute(query);
					System.out.println("DONE EXECUTING COMMAND" + queryString);
					currentCommand = null;
					// ensure cmd.result is not null
					if(cmd.result == null) {
						cmd.result = new QueryYieldsNullException(query);
						System.out.println("RESULT IS NULL");
					}
					synchronized(cmd) {
						cmd.notifyAll();
					}
				}
			}
		}
		catch(Exception exc) {
			// Notify caller that command finished
			for(QueryCommand x : commadQueue) {
				x.result = exc;
				synchronized(x) {
					x.notifyAll();
				}
			}
			if(cmd != null) {
				cmd.result = exc;
				synchronized(cmd) {
					cmd.notifyAll();
				}
			}
		}

		isClosed = true;
		isRunning = false;
		query.close();
	}

	public void close() {
		if(!isClosed && isRunning) {
			isRunning = false;
			// Notify caller that command finished (e.g., in case query was closed when a command
			// did not completed yet)
			for(QueryCommand cmd : commadQueue) {
				if(cmd.result==null) cmd.result = new QueryClosedException(query);
				synchronized(cmd) { cmd.notifyAll(); }
			}
			// FIXME: what happens if thread is stuck with a command that does not terminate?

			if(currentCommand!=null) {
				System.out.println("ABORTED THREAD BEFORE FINISHING");
				currentCommand.result = new QueryClosedException(query);
				synchronized(currentCommand) { currentCommand.notifyAll(); }
				currentCommand = null;
				System.out.println("DONE ABORTED THREAD BEFORE FINISHING");
			}
			// wake up query thread so that it can terminate after close was called
			synchronized (this) {
				this.notifyAll();
			}
		}
	}

	private Object runCommand(QueryCommand cmd) throws Exception {
		System.out.println("WAIT FOR THE THREAD ... " +queryString);
		waitOnThread();
		System.out.println("DONE WAITING FOR THE THREAD " +queryString);

		if(exception!= null) {
			throw exception;
		}

		if(isClosed || !isRunning) {
		  throw new InterruptedException("Thread not running for query.");
		}

		cmd.result = null;
		// add command to queue which is processed in query thread
		System.out.println("PREPARE TO PUSH COMMAD QUEUE ... " +queryString);

		synchronized (commadQueue) {
			commadQueue.push(cmd);
		}

		System.out.println("DONE PREPARE TO PUSH COMMAD QUEUE " +queryString);
		// wake up query thread in case it is sleeping
		System.out.println("NOTIFY AAAAALLL " +queryString);

		synchronized (this) {
			this.notifyAll();
		}

		System.out.println("DONE NOTIFY AAAAALLL " + queryString);
		// wait until query thread processed the command
		if(cmd.result==null) {
			synchronized(cmd) {
				try {
					System.out.println("PROCESSING COMMAND WAITING ...... " + queryString);
					cmd.wait(300000);
					//cmd.wait();
					if (cmd.result == null){
						cmd.result = new Exception("DEAD LOCK APPEARED " + queryString);
					}
					System.out.println("DONE PROCESSING COMMAND WAITING " + queryString);
				}
				catch (Exception e) {}
			}
			System.out.println("DONE PROCESSING COMMAND ...... " +queryString);
		}
		//cmd.result = new Exception("DEAD LOCK APPEARED");
		// handle query result. in case it's an exception, throw it!
		if(cmd.result instanceof Exception){
			System.out.println("BAD EXECEPTION "+ cmd.toString());
			throw (Exception)cmd.result;
		}

		System.out.println("DONE WITH COMMAND EXECUTION " +cmd);
		return cmd.result;
	}
	
	public boolean isRunning() {
		return isRunning;
	}

	public boolean isStarted() {
		return isStarted;
	}

	public void reset() throws Exception {
		runCommand(new ResetCommand());
	}

	public boolean hasMoreSolutions() throws Exception {
		return ((Boolean)runCommand(new HasMoreSolutionsCommand())).booleanValue();
	}

	@SuppressWarnings("unchecked")
	public java.util.Map<String, Term> nextSolution() throws Exception {
		return (java.util.Map<String, Term>)runCommand(new NextSolutionCommand());
	}

	@SuppressWarnings("unchecked")
	public Map<String, Term>[] allSolutions() throws Exception {
		System.out.println("UNCHECK ALL SOLUTIONS AREA");
		return (Map<String, Term>[])runCommand(new AllSolutionsCommand());
	}
}
