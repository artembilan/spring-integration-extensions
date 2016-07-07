/*
 * Copyright 2016 the original author or authors.
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

package org.springframework.integration.etcd.leader;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.SmartLifecycle;
import org.springframework.integration.leader.Candidate;
import org.springframework.integration.leader.Context;
import org.springframework.integration.leader.DefaultCandidate;
import org.springframework.integration.leader.event.DefaultLeaderEventPublisher;
import org.springframework.integration.leader.event.LeaderEventPublisher;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.util.Assert;

import mousio.etcd4j.EtcdClient;
import mousio.etcd4j.responses.EtcdAuthenticationException;
import mousio.etcd4j.responses.EtcdException;

/**
 * Bootstrap leadership {@link Candidate candidates} with etcd.
 *
 * @author Venil Noronha
 * @author Patrick Peralta
 * @author Artem Bilan
 */
public class LeaderInitiator implements SmartLifecycle, DisposableBean, ApplicationEventPublisherAware {

	private static final Log logger = LogFactory.getLog(LeaderInitiator.class);

	/**
	 * TTL for etcd entry in seconds.
	 */
	private final static int TTL = 10;

	/**
	 * Number of seconds to sleep between issuing heartbeats.
	 */
	private final static int HEART_BEAT_SLEEP = TTL / 2;

	/**
	 * Default namespace for etcd entry.
	 */
	private final static String DEFAULT_NAMESPACE = "spring-cloud";

	/**
	 * Executor service for running leadership daemon.
	 */
	private final ExecutorService leaderExecutorService =
			Executors.newSingleThreadExecutor(new DaemonCustomizableThreadFactory("Etcd-Leadership"));

	/**
	 * Executor service for running leadership worker daemon.
	 */
	private final ExecutorService workerExecutorService =
			Executors.newSingleThreadExecutor(new DaemonCustomizableThreadFactory("Etcd-Leadership-Worker"));

	/**
	 * The {@link EtcdContext} instance.
	 */
	private final EtcdContext context = new EtcdContext();

	/**
	 * {@link EtcdClient} instance.
	 */
	private final EtcdClient client;

	/**
	 * Candidate for leader election.
	 */
	private final Candidate candidate;

	/**
	 * The base etcd path where candidate id is to be stored.
	 */
	private final String baseEtcdPath;

	/**
	 * Flag that indicates whether the current candidate is
	 * the leader.
	 */
	private volatile boolean isLeader = false;

	/**
	 * Flag that indicates whether the current candidate's
	 * leadership should be relinquished.
	 */
	private volatile boolean relinquishLeadership = false;

	/**
	 * Future returned by submitting a {@link Initiator} to {@link #leaderExecutorService}.
	 * This is used to cancel leadership.
	 */
	private volatile Future<Void> initiatorFuture;

	/**
	 * Future returned by submitting a {@link Worker} to {@link #workerExecutorService}.
	 * This is used to notify leadership revocation.
	 */
	private volatile Future<Void> workerFuture;

	/**
	 * Leader event publisher.
	 */
	private volatile LeaderEventPublisher leaderEventPublisher = new DefaultLeaderEventPublisher();

	private boolean customPublisher = false;

	/**
	 * @see SmartLifecycle
	 */
	private volatile boolean autoStartup = true;

	/**
	 * @see SmartLifecycle which is an extension of org.springframework.context.Phased
	 */
	private volatile int phase;

	/**
	 * Flag that indicates whether the leadership election for
	 * this {@link #candidate} is running.
	 */
	private volatile boolean running;


	/**
	 * Construct a {@link LeaderInitiator}.
	 *
	 * @param client     {@link EtcdClient} instance
	 * @param namespace	 Etcd namespace
	 */
	public LeaderInitiator(EtcdClient client, String namespace) {
		this(client, new DefaultCandidate(), namespace);
	}

	/**
	 * Construct a {@link LeaderInitiator}.
	 *
	 * @param client     {@link EtcdClient} instance
	 * @param candidate  leadership election candidate
	 * @param namespace	 Etcd namespace
	 */
	public LeaderInitiator(EtcdClient client, Candidate candidate, String namespace) {
		Assert.notNull(client, "'client' must not be null");
		Assert.notNull(candidate, "'candidate' must not be null");
		this.client = client;
		this.candidate = candidate;
		this.baseEtcdPath = (namespace == null ? DEFAULT_NAMESPACE : namespace) + "/" + candidate.getRole();
	}

	/**
	 * Sets the {@link LeaderEventPublisher}.
	 *
	 * @param leaderEventPublisher the event publisher
	 */
	public void setLeaderEventPublisher(LeaderEventPublisher leaderEventPublisher) {
		Assert.notNull(leaderEventPublisher);
		this.leaderEventPublisher = leaderEventPublisher;
		this.customPublisher = true;
	}

	@Override
	public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
		if (!this.customPublisher) {
			this.leaderEventPublisher = new DefaultLeaderEventPublisher(applicationEventPublisher);
		}
	}

	public void setAutoStartup(boolean autoStartup) {
		this.autoStartup = autoStartup;
	}

	@Override
	public boolean isAutoStartup() {
		return this.autoStartup;
	}

	public void setPhase(int phase) {
		this.phase = phase;
	}


	@Override
	public int getPhase() {
		return this.phase;
	}

	/**
	 * Start the registration of the {@link #candidate} for leader election.
	 */
	@Override
	public synchronized void start() {
		if (!this.running) {
			this.running = true;
			this.initiatorFuture = this.leaderExecutorService.submit(new Initiator());
		}
	}

	@Override
	public void stop(Runnable callback) {
		stop();
		if (callback != null) {
			callback.run();
		}
	}

	/**
	 * Stop the registration of the {@link #candidate} for leader election.
	 * If the candidate is currently leader, its leadership will be revoked.
	 */
	@Override
	public synchronized void stop() {
		if (this.running) {
			this.running = false;
			this.initiatorFuture.cancel(true);
		}
	}

	/**
	 * @return true if leadership election for this {@link #candidate} is running
	 */
	@Override
	public boolean isRunning() {
		return this.running;
	}

	@Override
	public void destroy() throws Exception {
		stop();
		this.workerExecutorService.shutdown();
		this.leaderExecutorService.shutdown();
	}

	/**
	 * Notifies that the candidate has acquired leadership.
	 */
	private void notifyGranted() {
		this.isLeader = true;
		this.leaderEventPublisher.publishOnGranted(LeaderInitiator.this, this.context, this.candidate.getRole());
		this.workerFuture = this.workerExecutorService.submit(new Worker());
	}

	/**
	 * Notifies that the candidate's leadership was revoked.
	 *
	 * @throws InterruptedException	if the current thread was interrupted while waiting
	 * for the worker thread to finish.
	 */
	private void notifyRevoked() throws InterruptedException {
		this.isLeader = false;
		this.leaderEventPublisher.publishOnRevoked(LeaderInitiator.this, this.context, this.candidate.getRole());
		this.workerFuture.cancel(true);
		try {
			this.workerFuture.get();
		}
		catch (InterruptedException e) {
			throw e;
		}
		catch (CancellationException e) {
			// Consume
		}
		catch (ExecutionException e) {
			logger.error("Exception thrown by candidate", e.getCause());
		}
	}

	/**
	 * Tries to delete the candidate's entry from etcd.
	 */
	private void tryDeleteCandidateEntry() throws EtcdAuthenticationException {
		try {
			this.client.delete(this.baseEtcdPath)
					.prevValue(this.candidate.getId())
					.send()
					.get();
		}
		catch (EtcdException e) {
			logger.warn("Couldn't delete candidate's entry from etcd", e);
		}
		catch (IOException | TimeoutException e) {
			logger.warn("Couldn't access etcd", e);
		}
	}

	/**
	 * Callable that invokes {@link Candidate#onGranted(Context)}
	 * when the candidate is granted leadership.
	 */
	class Worker implements Callable<Void> {

		@Override
		public Void call() throws InterruptedException {
			try {
				LeaderInitiator.this.candidate.onGranted(LeaderInitiator.this.context);
				Thread.sleep(Long.MAX_VALUE);
			}
			finally {
				LeaderInitiator.this.relinquishLeadership = true;
				LeaderInitiator.this.candidate.onRevoked(LeaderInitiator.this.context);
			}
			return null;
		}

	}

	/**
	 * Callable that manages the etcd heart beats for leadership election.
	 */
	class Initiator implements Callable<Void> {

		@Override
		public Void call() throws Exception {
			try {
				while (LeaderInitiator.this.running) {
					if (LeaderInitiator.this.relinquishLeadership) {
						relinquishLeadership();
						LeaderInitiator.this.relinquishLeadership = false;
					}
					else if (LeaderInitiator.this.isLeader) {
						sendHeartBeat();
					}
					else {
						tryAcquire();
					}
					TimeUnit.SECONDS.sleep(HEART_BEAT_SLEEP);
				}
			}
			finally {
				if (LeaderInitiator.this.isLeader) {
					relinquishLeadership();
				}
			}
			return null;
		}

		/**
		 * Relinquishes leadership of current candidate by deleting candidate's
		 * entry from etcd and then notifies that the current candidate is no
		 * longer leader.
		 *
		 * @throws InterruptedException	if the current thread was interrupted
		 * while notifying revocation.
		 */
		private void relinquishLeadership() throws Exception {
			tryDeleteCandidateEntry();
			notifyRevoked();
		}

		/**
		 * Sends a heart beat to maintain leadership by refreshing the ttl of
		 * the etcd key. If the key has a different value during the call, it is
		 * assumed that the current candidate's leadership is revoked. If access
		 * to etcd fails, then the the current candidate's leadership is
		 * relinquished.
		 *
		 * @throws InterruptedException	if the current thread was interrupted
		 * while notifying revocation.
		 */
		private void sendHeartBeat() throws Exception {
			try {
				LeaderInitiator.this.client.put(LeaderInitiator.this.baseEtcdPath, LeaderInitiator.this.candidate.getId())
						.ttl(TTL)
						.prevValue(LeaderInitiator.this.candidate.getId())
						.send()
						.get();
			}
			catch (EtcdException e) {
				notifyRevoked();
			}
			catch (IOException | TimeoutException e) {
				// Couldn't access etcd, therefore, relinquish leadership
				logger.error("Couldn't access etcd, relinquishing leadership...", e);
				notifyRevoked();
			}
		}

		/**
		 * Tries to acquire leadership by posting the candidate's id to etcd. If the etcd call
		 * is successful, it is assumed that the current candidate is now leader.
		 */
		private void tryAcquire() throws EtcdAuthenticationException {
			try {
				LeaderInitiator.this.client.put(LeaderInitiator.this.baseEtcdPath,
						LeaderInitiator.this.candidate.getId())
						.ttl(TTL)
						.prevExist(false)
						.send()
						.get();
				notifyGranted();
			}
			catch (EtcdException e) {
				// Couldn't set the value to current candidate's id, therefore, keep trying.
			}
			catch (IOException | TimeoutException e) {
				// Couldn't access etcd, therefore, keep trying.
				logger.warn("Couldn't access etcd", e);
			}
		}

	}

	/**
	 * Implementation of leadership context backed by Etcd.
	 */
	class EtcdContext implements Context {

		@Override
		public boolean isLeader() {
			return LeaderInitiator.this.isLeader;
		}

		@Override
		public void yield() {
			if (LeaderInitiator.this.isLeader) {
				LeaderInitiator.this.relinquishLeadership = true;
			}
		}

		@Override
		public String toString() {
			return "EtcdContext{role=" + LeaderInitiator.this.candidate.getRole() +
					", id=" + LeaderInitiator.this.candidate.getId() +
					", isLeader=" + isLeader() + "}";
		}

	}

	private static final class DaemonCustomizableThreadFactory extends CustomizableThreadFactory {

		DaemonCustomizableThreadFactory(String threadNamePrefix) {
			super(threadNamePrefix);
			setDaemon(true);
		}

	}

}
