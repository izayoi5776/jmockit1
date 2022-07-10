/*
 * Copyright (c) 2006 JMockit developers
 * This file is subject to the terms of the MIT license (see LICENSE.txt).
 */
package mockit.internal.startup;

import java.lang.instrument.*;
import javax.annotation.*;

import mockit.internal.*;
import mockit.internal.expectations.transformation.*;
import mockit.internal.state.*;
import static mockit.internal.startup.ClassLoadingBridgeFields.createSyntheticFieldsInJREClassToHoldClassLoadingBridges;

/**
 * This is the "agent class" that initializes the JMockit "Java agent", provided the JVM is initialized with
 * <tt>-javaagent:&lt;properPathTo>/jmockit-1-x.jar</tt>.
 *
 * @see #premain(String, Instrumentation)
 */
public final class Startup
{
   @Nullable private static Instrumentation instrumentation;
   public static boolean initializing;

   private Startup() {}

   /**
    * User-specified fakes will applied at this time, if the "fakes" system property is set to the fully qualified class names.
    *
    * @param agentArgs if "coverage", the coverage tool is activated<br>
    *        在 pom.xml 中配置 <em>-javaagent:../agent.jar=coverage</em> 开启覆盖率工具  
    * @param inst      the instrumentation service provided by the JVM
    */
   public static void premain(@Nullable String agentArgs, @Nonnull Instrumentation inst) {
      createSyntheticFieldsInJREClassToHoldClassLoadingBridges(inst);

      instrumentation = inst;
      inst.addTransformer(CachedClassfiles.INSTANCE, true);

      initializing = true;
      try { JMockitInitialization.initialize(inst, "coverage".equals(agentArgs)); } finally { initializing = false; }

      inst.addTransformer(new ExpectationsTransformer());
   }

   /**
    * instrumentation 的 getter()
    */
   @Nonnull @SuppressWarnings("ConstantConditions")
   public static Instrumentation instrumentation() { return instrumentation; }

   /**
    * 臭名昭著的初始化检查
    */
   public static void verifyInitialization() {
      if (instrumentation == null) {
         throw new IllegalStateException(
            "JMockit didn't get initialized; please check the -javaagent JVM initialization parameter was used");
      }
   }

   /**
    * 执行类转换。无视例外。
    */
   @SuppressWarnings("ConstantConditions")
   public static void retransformClass(@Nonnull Class<?> aClass) {
      try { instrumentation.retransformClasses(aClass); } catch (UnmodifiableClassException ignore) {}
   }

   /**
    * 用 modifiedClassfile 重定义 classToRedefine 里面的类。 
    */
   public static void redefineMethods(@Nonnull ClassIdentification classToRedefine, @Nonnull byte[] modifiedClassfile) {
      Class<?> loadedClass = classToRedefine.getLoadedClass();
      redefineMethods(loadedClass, modifiedClassfile);
   }

   /**
    * 用 modifiedClassfile 重定义 classToRedefine 里面的类。 
    */
   public static void redefineMethods(@Nonnull Class<?> classToRedefine, @Nonnull byte[] modifiedClassfile) {
      redefineMethods(new ClassDefinition(classToRedefine, modifiedClassfile));
   }

   /**
    * 用 classDefs 重定义类。不影响现有实例，不初始化。
    */
   public static void redefineMethods(@Nonnull ClassDefinition... classDefs) {
      try {
         //noinspection ConstantConditions
         instrumentation.redefineClasses(classDefs);
      }
      catch (ClassNotFoundException | UnmodifiableClassException e) {
         throw new RuntimeException(e); // should never happen
      }
      // 把 InternalError 转换成更可读的 RuntimeException
      catch (InternalError ignore) {
         // If a class to be redefined hasn't been loaded yet, the JVM may get a NoClassDefFoundError during
         // redefinition. Unfortunately, it then throws a plain InternalError instead.
         for (ClassDefinition classDef : classDefs) {
            detectMissingDependenciesIfAny(classDef.getDefinitionClass());
         }

         // If the above didn't throw upon detecting a NoClassDefFoundError, then ignore the original error and
         // continue, in order to prevent secondary failures.
      }
   }

   /**
    * 确认依存类存在，没有则throw例外 
    */
   private static void detectMissingDependenciesIfAny(@Nonnull Class<?> mockedClass) {
      try {
         Class.forName(mockedClass.getName(), false, mockedClass.getClassLoader());
      }
      catch (NoClassDefFoundError e) {
         throw new RuntimeException("Unable to mock " + mockedClass + " due to a missing dependency", e);
      }
      catch (ClassNotFoundException ignore) {
         // Shouldn't happen since the mocked class would already have been found in the classpath.
      }
   }

   /**
    * 取得类，没load则返回null
    */
   @Nullable
   public static Class<?> getClassIfLoaded(@Nonnull String classDescOrName) {
      String className = classDescOrName.replace('/', '.');
      @SuppressWarnings("ConstantConditions") Class<?>[] loadedClasses = instrumentation.getAllLoadedClasses();

      for (Class<?> aClass : loadedClasses) {
         if (aClass.getName().equals(className)) {
            return aClass;
         }
      }

      return null;
   }
}