package akka.streams.impl

import akka.streams.Operation
import Operation._
import ops._
import asyncrx.api.Producer
import akka.util.Collections.EmptyImmutableSeq

object OperationImpl {
  def apply[A](ctx: ContextEffects, p: Pipeline[A]): SyncRunnable =
    ComposeImpl.pipeline(apply[A](_: Downstream[A], ctx, p.source), apply[A](_: Upstream, ctx, p.sink))

  def apply[I](upstream: Upstream, ctx: ContextEffects, sink: Sink[I]): SyncSink[I] =
    sink match {
      case Foreach(f)          ⇒ new ForeachImpl(upstream, f)
      case FromConsumerSink(s) ⇒ new FromConsumerSinkImpl(upstream, ctx, s)
    }

  def apply[O](downstream: Downstream[O], ctx: ContextEffects, source: Source[O]): SyncSource =
    source match {
      //Recursive
      case m: MappedSource[i, O]                      ⇒ ComposeImpl.source[i](apply(_: Downstream[i], ctx, m.source), apply(_, downstream, ctx, m.operation))
      case ConcatSources(s1, s2)                      ⇒ apply(downstream, ctx, Seq(s1, s2).toSource.flatten)

      //Non-recursive
      case FromIterableSource(s)                      ⇒ new FromIterableSourceImpl(downstream, ctx, s)
      case FromProducerSource(i: InternalProducer[O]) ⇒ i.createSource(downstream)
      case FromProducerSource(p)                      ⇒ new FromProducerSourceImpl(downstream, ctx, p)
      case FromFutureSource(f)                        ⇒ new FromFutureSourceImpl(downstream, ctx, f)
      case SingletonSource(element)                   ⇒ new SingletonSourceImpl(downstream, element)
      case EmptySource                                ⇒ new EmptySourceImpl(downstream)
    }

  def apply[I, O](upstream: Upstream, downstream: Downstream[O], ctx: ContextEffects, op: Operation[I, O]): SyncOperation[I] = {
    /** operation implementations that can be implemented in terms of another implementation */
    def translate[I, O](op: Operation[I, O]): Operation[I, O] = op match {
      case FlatMap(f) ⇒ Map(f).flatten
      case Identity() ⇒ Map(i ⇒ i.asInstanceOf[O])
      case x          ⇒ x //FIXME This is going to be a cause for missed implementations
    }

    translate(op) match {
      case a: Compose[I, i2, O] ⇒ ComposeImpl.operation(apply(upstream, _: Downstream[i2], ctx, a.f), apply(_, downstream, ctx, a.g))
      case Map(f)               ⇒ new MapImpl(upstream, downstream, f)
      case Flatten()            ⇒ new FlattenImpl(upstream, downstream, ctx).asInstanceOf[SyncOperation[I]]
      case d: Fold[I, O]        ⇒ new FoldImpl(upstream, downstream, d)
      case u: Process[I, O, _]  ⇒ new ProcessImpl(upstream, downstream, u)
      case s: Span[I]           ⇒ new SpanImpl(upstream, downstream.asInstanceOf[Downstream[Source[I]]], ctx, s)
      case ExposeProducer()     ⇒ new ExposeProducerImpl(upstream, downstream.asInstanceOf[Downstream[Producer[I]]], ctx).asInstanceOf[SyncOperation[I]]
      case SourceHeadTail()     ⇒ new SourceHeadTailImpl(upstream, downstream.asInstanceOf[Downstream[(I, Source[I])]], ctx).asInstanceOf[SyncOperation[I]]
    }
  }
}