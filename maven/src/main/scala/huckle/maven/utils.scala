/*
 * Copyright 2023 Arman Bilge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package huckle.maven

import cats.effect.kernel.Concurrent
import cats.effect.kernel.Outcome
import cats.syntax.all.*

object utils:
  /** Races two computations that return `Option[A]`. The first `Some` wins and the other
    * computation is cancelled. `None` signals "forfeiting" - if one side returns `None`, we
    * wait for the other. If both return `None`, the result is `None`.
    */
  def raceOption[F[_], A](left: F[Option[A]], right: F[Option[A]])(using
      F: Concurrent[F],
  ): F[Option[A]] =
    F.uncancelable { poll =>
      poll(F.racePair(left, right)).flatMap {
        case Left((oc, f)) =>
          oc match
            case Outcome.Succeeded(fa) =>
              fa.flatMap {
                case Some(a) => f.cancel.as(Some(a))
                case None =>
                  poll(f.join).flatMap {
                    case Outcome.Succeeded(fb) => fb
                    case Outcome.Errored(e) => F.raiseError(e)
                    case Outcome.Canceled() => poll(F.canceled) *> F.never
                  }
              }
            case Outcome.Errored(e) => f.cancel *> F.raiseError(e)
            case Outcome.Canceled() =>
              f.cancel *> f.join.flatMap {
                case Outcome.Succeeded(fb) => fb
                case Outcome.Errored(e) => F.raiseError(e)
                case Outcome.Canceled() => poll(F.canceled) *> F.never
              }

        case Right((f, oc)) =>
          oc match
            case Outcome.Succeeded(fb) =>
              fb.flatMap {
                case Some(b) => f.cancel.as(Some(b))
                case None =>
                  poll(f.join).flatMap {
                    case Outcome.Succeeded(fa) => fa
                    case Outcome.Errored(e) => F.raiseError(e)
                    case Outcome.Canceled() => poll(F.canceled) *> F.never
                  }
              }
            case Outcome.Errored(e) => f.cancel *> F.raiseError(e)
            case Outcome.Canceled() =>
              f.cancel *> f.join.flatMap {
                case Outcome.Succeeded(fa) => fa
                case Outcome.Errored(e) => F.raiseError(e)
                case Outcome.Canceled() => poll(F.canceled) *> F.never
              }
      }
    }
