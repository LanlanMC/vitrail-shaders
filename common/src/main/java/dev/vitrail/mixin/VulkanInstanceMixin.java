package dev.vitrail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import dev.vitrail.Vitrail;
import dev.vitrail.render.streamline.StreamlineBridge;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(VulkanInstance.class)
public abstract class VulkanInstanceMixin {

	@WrapOperation(
			method = "<init>",
			at = @At(
					value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/VK12;vkCreateInstance(Lorg/lwjgl/vulkan/VkInstanceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"
			)
	)
	private int vitrail$createStreamlineInstance(
			VkInstanceCreateInfo pCreateInfo,
			VkAllocationCallbacks pAllocator,
			PointerBuffer pOutInstance,
			Operation<Integer> original
	) {
		if (StreamlineBridge.isCandidate() && StreamlineBridge.prepareStreamline()) {
			try {
				long allocAddr = pAllocator != null ? pAllocator.address() : 0L;
				int res = StreamlineBridge.streamlineCreateInstance(pCreateInfo.address(), allocAddr, pOutInstance.address());
				if (res == 0) {
					Vitrail.logger().info("Streamline vkCreateInstance succeeded");
					return res;
				}
				Vitrail.logger().warn("Streamline vkCreateInstance failed with code 0x{}, falling back to native Vulkan instance", Integer.toHexString(res));
				StreamlineBridge.rejectStreamline();
				pOutInstance.put(pOutInstance.position(), 0L);
			} catch (Throwable t) {
				Vitrail.logger().warn("Streamline vkCreateInstance exception, falling back to native Vulkan instance: {}", t.getMessage());
				StreamlineBridge.rejectStreamline();
				pOutInstance.put(pOutInstance.position(), 0L);
			}
		}
		return original.call(pCreateInfo, pAllocator, pOutInstance);
	}
}
