module uart_top_apb (
      input  wire        reset
    , input  wire        clock
    , input  wire        in_psel
    , input  wire        in_penable
    , input  wire [ 2:0] in_pprot
    , output reg         in_pready
    , output wire        in_pslverr
    , input  wire [31:0] in_paddr
    , input  wire        in_pwrite
    , output reg  [31:0] in_prdata
    , input  wire [31:0] in_pwdata
    , input  wire [ 3:0] in_pstrb
    , input  wire        uart_rx     // serial input
    , output wire        uart_tx     // serial output
);
  //--------------------------------------------------
  wire       ctsn = 1'b0;
  wire       dtr_pad_o;
  wire       dsr_pad_i = 1'b0;
  wire       ri_pad_i = 1'b0;
  wire       dcd_pad_i = 1'b0;
  wire       interrupt;
  //--------------------------------------------------------
  reg        reg_we;  // Write enable for registers
  reg        reg_re;  // Read enable for registers
  reg  [2:0] reg_adr;
  reg  [7:0] reg_dat8_w;  // write to reg
  wire [7:0] reg_dat8_r;  // read from reg
  wire       rts_internal;
  //--------------------------------------------------------
  // 状态机定义
  localparam S_IDLE  = 3'd0;
  localparam S_BYTE0 = 3'd1;
  localparam S_BYTE1 = 3'd2;
  localparam S_BYTE2 = 3'd3;
  localparam S_BYTE3 = 3'd4;
  localparam S_DONE  = 3'd5;

  reg [2:0] state;
  reg [3:0] pstrb_reg;      // 锁存 pstrb
  reg [31:0] paddr_reg;     // 锁存地址
  reg [31:0] pwdata_reg;    // 锁存写数据
  reg        pwrite_reg;    // 锁存读写方向
  reg [31:0] prdata_acc;    // 累积读取数据

  assign in_pslverr = 1'b0;

  // 状态机 + 控制信号 (全同步时序逻辑)
  always @(posedge clock) begin
    if (reset) begin
      state      <= S_IDLE;
      pstrb_reg  <= 4'b0;
      paddr_reg  <= 32'b0;
      pwdata_reg <= 32'b0;
      pwrite_reg <= 1'b0;
      prdata_acc <= 32'b0;
      reg_we     <= 1'b0;
      reg_re     <= 1'b0;
      reg_adr    <= 3'b0;
      reg_dat8_w <= 8'b0;
      in_pready  <= 1'b0;
      in_prdata  <= 32'b0;
    end else begin
      // 默认清除控制信号
      reg_we    <= 1'b0; // write enable pulse
      reg_re    <= 1'b0; // read enable pulse
      in_pready <= 1'b0; // ready pulse

      case (state)
        S_IDLE: begin
          prdata_acc <= 32'b0;
          if (in_psel && !in_penable) begin
            pstrb_reg  <= in_pstrb;
            paddr_reg  <= in_paddr;
            pwdata_reg <= in_pwdata;
            pwrite_reg <= in_pwrite;
            if (in_pstrb[0]) begin
              reg_adr <= in_paddr[2:0] + 3'd0;
              reg_dat8_w <= in_pwdata[7:0];
              reg_we <= in_pwrite;
              reg_re <= ~in_pwrite;
            end
            state <= S_BYTE0;
          end
        end

        S_BYTE0: begin
          if (pstrb_reg[0] && !pwrite_reg) begin
            prdata_acc[7:0] <= reg_dat8_r;
          end
          if (pstrb_reg[1]) begin
            reg_adr <= paddr_reg[2:0] + 3'd1;
            reg_dat8_w <= pwdata_reg[15:8];
            reg_we <= pwrite_reg;
            reg_re <= ~pwrite_reg;
          end
          state <= S_BYTE1;
        end

        S_BYTE1: begin
          if (pstrb_reg[1] && !pwrite_reg) begin
            prdata_acc[15:8] <= reg_dat8_r;
          end
          if (pstrb_reg[2]) begin
            reg_adr <= paddr_reg[2:0] + 3'd2;
            reg_dat8_w <= pwdata_reg[23:16];
            reg_we <= pwrite_reg;
            reg_re <= ~pwrite_reg;
          end
          state <= S_BYTE2;
        end

        S_BYTE2: begin
          if (pstrb_reg[2] && !pwrite_reg) begin
            prdata_acc[23:16] <= reg_dat8_r;
          end
          if (pstrb_reg[3]) begin
            reg_adr <= paddr_reg[2:0] + 3'd3;
            reg_dat8_w <= pwdata_reg[31:24];
            reg_we <= pwrite_reg;
            reg_re <= ~pwrite_reg;
          end
          state <= S_BYTE3;
        end

        S_BYTE3: begin
          if (pstrb_reg[3] && !pwrite_reg) begin
            prdata_acc[31:24] <= reg_dat8_r;
          end
          state <= S_DONE;
        end

        S_DONE: begin
          in_prdata <= prdata_acc;
          if (in_psel && in_penable) begin
            in_pready <= 1'b1;
          end
          state <= S_IDLE;
        end

        default: state <= S_IDLE;
      endcase
    end
  end

  //--------------------------------------------------------
  uart_regs Uregs (
      .clk         (clock),
      .wb_rst_i    (reset),
      .wb_addr_i   (reg_adr),
      .wb_dat_i    (reg_dat8_w),
      .wb_dat_o    (reg_dat8_r),
      .wb_we_i     (reg_we),
      .wb_re_i     (reg_re),
      .modem_inputs({~ctsn, dsr_pad_i, ri_pad_i, dcd_pad_i}),
      .stx_pad_o   (uart_tx),
      .srx_pad_i   (uart_rx),
      .rts_pad_o   (rts_internal),
      .dtr_pad_o   (dtr_pad_o),
      .int_o       (interrupt)
  );
endmodule
