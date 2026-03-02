project('npc', 'cpp',
  version: '0.1.0',
  default_options: ['cpp_std=c++20'], # do nothing here, just for compatibility with other projects
)

# ======================== SoCConfig.scala generation ========================
# meson is used solely to generate SoCConfig.scala from a template,
# substituting SoC address map values from meson_options.txt.

conf = configuration_data()

# SoC address map
_soc_map = {
  'soc_reset_vector':  'CONFIG_SOC_RESET_VECTOR',
  'soc_uart_base':     'CONFIG_SOC_UART_BASE',
  'soc_uart_size':     'CONFIG_SOC_UART_SIZE',
  'soc_gpio_base':     'CONFIG_SOC_GPIO_BASE',
  'soc_gpio_size':     'CONFIG_SOC_GPIO_SIZE',
  'soc_keyboard_base': 'CONFIG_SOC_KEYBOARD_BASE',
  'soc_keyboard_size': 'CONFIG_SOC_KEYBOARD_SIZE',
  'soc_vga_base':      'CONFIG_SOC_VGA_BASE',
  'soc_vga_size':      'CONFIG_SOC_VGA_SIZE',
  'soc_spi_ctrl_base': 'CONFIG_SOC_SPI_CTRL_BASE',
  'soc_spi_ctrl_size': 'CONFIG_SOC_SPI_CTRL_SIZE',
  'soc_flash_base':    'CONFIG_SOC_XIP_FLASH_BASE',
  'soc_flash_size':    'CONFIG_SOC_XIP_FLASH_SIZE',
  'soc_psram_base':    'CONFIG_SOC_PSRAM_BASE',
  'soc_psram_size':    'CONFIG_SOC_PSRAM_SIZE',
  'soc_mrom_base':     'CONFIG_SOC_MROM_BASE',
  'soc_mrom_size':     'CONFIG_SOC_MROM_SIZE',
  'soc_sram_base':     'CONFIG_SOC_SRAM_BASE',
  'soc_sram_size':     'CONFIG_SOC_SRAM_SIZE',
  'soc_sdram_base':    'CONFIG_SOC_SDRAM_BASE',
  'soc_sdram_size':    'CONFIG_SOC_SDRAM_SIZE',
}
foreach opt_name, def_name : _soc_map
  conf.set(def_name, get_option(opt_name))
endforeach

configure_file(
  input: 'src/scala/SoCConfig.scala.in',
  output: 'SoCConfig.scala',
  configuration: conf,
)
