/*
 * Copyright (c) 2011-2013 GoPivotal, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.rx.action;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Dispatcher;
import reactor.function.Consumer;
import reactor.function.Supplier;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * @author Stephane Maldini
 * @since 2.0
 */
public class BufferShiftWhenAction<T> extends Action<T, List<T>> {

	private final List<List<T>> buckets = new LinkedList<>();
	private final Supplier<? extends Publisher<?>> bucketClosing;
	private final Publisher<?>                     bucketOpening;

	public BufferShiftWhenAction(Dispatcher dispatcher, Publisher<?> bucketOpenings, Supplier<? extends Publisher<?>>
			boundarySupplier) {
		super(dispatcher);
		this.bucketClosing = boundarySupplier;
		this.bucketOpening = bucketOpenings;
	}

	@Override
	protected void doSubscribe(Subscription subscription) {
		super.doSubscribe(subscription);

		bucketOpening.subscribe(new Subscriber<Object>() {
			Subscription s;

			@Override
			public void onSubscribe(Subscription s) {
				this.s = s;
				s.request(1l);
			}

			@Override
			public void onNext(Object o) {
				dispatch(new Consumer<Void>() {
					@Override
					public void accept(Void aVoid) {
						List<T> newBucket = new ArrayList<T>();
						buckets.add(newBucket);
						bucketClosing.get().subscribe(new BucketConsumer(newBucket));
					}
				});

				if (s != null) {
					s.request(1);
				}
			}

			@Override
			public void onError(Throwable t) {
				if (s != null) {
					s.cancel();
				}
				BufferShiftWhenAction.this.onError(t);
			}

			@Override
			public void onComplete() {
				if (s != null) {
					s.cancel();
				}
				BufferShiftWhenAction.this.onComplete();
			}
		});

	}

	@Override
	protected void doError(Throwable ev) {
		buckets.clear();
		super.doError(ev);
	}

	@Override
	protected void doComplete() {
		if (!buckets.isEmpty()) {
			for (List<T> bucket : buckets)
				broadcastNext(bucket);
		}
		buckets.clear();
		super.doComplete();
	}

	@Override
	protected void doNext(T value) {
		if(!buckets.isEmpty()){
			for(List<T> bucket : buckets){
				bucket.add(value);
			}
		}
	}

	private class BucketConsumer implements Subscriber<Object> {

		final List<T> bucket;
		Subscription s;

		public BucketConsumer(List<T> bucket) {
			this.bucket = bucket;
		}

		@Override
		public void onSubscribe(Subscription s) {
			this.s = s;
			s.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(Object o) {
			onComplete();
		}

		@Override
		public void onError(Throwable t) {
			if (s != null) {
				s.cancel();
			}
			BufferShiftWhenAction.this.onError(t);
		}

		@Override
		public void onComplete() {
			if (s != null) {
				s.cancel();
			}
			dispatch(new Consumer<Void>() {
				@Override
				public void accept(Void aVoid) {
					Iterator<List<T>> iterator = buckets.iterator();
					while(iterator.hasNext()){
						List<T> itBucket = iterator.next();
						if(itBucket == bucket){
							iterator.remove();
							broadcastNext(bucket);
							break;
						}
					}
				}
			});
		}
	}
}
