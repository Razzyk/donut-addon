package dev.mulveo.mvaddon.modules;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/** Client-thread cache. Replacing a chunk snapshot removes positions no longer present. */
final class StorageHitCache<C, P, T> {
   private final Map<C, Map<P, T>> chunks = new HashMap<>();

   void replace(C chunk, Map<P, T> hits) {
      if (hits.isEmpty()) chunks.remove(chunk); else chunks.put(chunk, new HashMap<>(hits));
   }

   T update(C chunk, P position, T type) {
      Map<P, T> hits = chunks.get(chunk);
      T old = hits == null ? null : hits.get(position);
      if (type != null) chunks.computeIfAbsent(chunk, ignored -> new HashMap<>()).put(position, type);
      else if (hits != null) {
         hits.remove(position);
         if (hits.isEmpty()) chunks.remove(chunk);
      }
      return old;
   }

   boolean remove(C chunk) { return chunks.remove(chunk) != null; }
   void clear() { chunks.clear(); }
   void removeIf(Predicate<C> predicate) { chunks.keySet().removeIf(predicate); }
   Map<P, T> flattened() {
      Map<P, T> result = new HashMap<>();
      chunks.values().forEach(result::putAll);
      return result;
   }
   record Hit<P, T>(P first, P second, T type) {}

   static <P, T> List<Hit<P, T>> mergePairs(Map<P, T> hits, T pairType, Function<P, P> partnerFor) {
      List<Hit<P, T>> result = new ArrayList<>();
      Set<P> consumed = new HashSet<>();
      for (Map.Entry<P, T> entry : hits.entrySet()) {
         P position = entry.getKey();
         T type = entry.getValue();
         if (Objects.equals(type, pairType)) {
            if (consumed.contains(position)) continue;
            P partner = partnerFor.apply(position);
            if (partner != null && !partner.equals(position) && Objects.equals(hits.get(partner), pairType)) {
               consumed.add(position);
               consumed.add(partner);
               result.add(new Hit<>(position, partner, type));
               continue;
            }
         }
         result.add(new Hit<>(position, position, type));
      }
      return result;
   }
}
