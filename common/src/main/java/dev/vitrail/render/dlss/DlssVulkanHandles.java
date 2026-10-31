package dev.vitrail.render.dlss;

import dev.vitrail.mixin.access.CommandEncoderAccessor;
import dev.vitrail.mixin.access.VulkanCommandEncoderAccessor;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Extracts raw 64-bit Vulkan addresses and format flags from Mojang's Blaze3D and LWJGL types
 * for passing to the native DLSS bridge.
 */
public final class DlssVulkanHandles {

	private DlssVulkanHandles() {
	}

	public static long vkDevice(VulkanDevice device) {
		return device == null || device.vkDevice() == null ? 0L : device.vkDevice().address();
	}

	public static long vkPhysicalDevice(VulkanDevice device) {
		if (device == null || device.vkDevice() == null) return 0L;
		return device.vkDevice().getPhysicalDevice() == null ? 0L : device.vkDevice().getPhysicalDevice().address();
	}

	public static long vkInstance(VulkanDevice device) {
		if (device != null && device.instance() != null && device.instance().vkInstance() != null) {
			return device.instance().vkInstance().address();
		}
		if (device == null || device.vkDevice() == null || device.vkDevice().getPhysicalDevice() == null) return 0L;
		return device.vkDevice().getPhysicalDevice().getInstance() == null
				? 0L
				: device.vkDevice().getPhysicalDevice().getInstance().address();
	}

	public static long vkCommandBuffer(CommandEncoder encoder) {
		if (encoder != null
				&& ((CommandEncoderAccessor) encoder).vitrail$backend() instanceof VulkanCommandEncoder vulkan) {
			VkCommandBuffer buffer = ((VulkanCommandEncoderAccessor) vulkan).vitrail$commandBuffer();
			return buffer == null ? 0L : buffer.address();
		}
		return 0L;
	}

	public static long vkImage(GpuTexture texture) {
		return texture instanceof VulkanGpuTexture image ? image.vkImage() : 0L;
	}

	public static long vkImageView(GpuTextureView view) {
		return view instanceof VulkanGpuTextureView texView ? texView.vkImageView() : 0L;
	}

	public static int vkFormat(GpuTexture texture) {
		return texture == null ? 0 : VulkanConst.toVk(texture.getFormat());
	}
}
