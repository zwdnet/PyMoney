# -*- coding:utf-8 -*-
# 初始化数据库，并把旧数据导入数据库
import pandas as pd
import Database as db

#从csv文件中读取数据存入dataform中
def ReadDataform(filename):
    df = pd.read_csv(filename)
    return df
    
#将dataform中的数据读入一个数组中
def ReadData(df):
    Time = df.Time
    Name = df.Name
    Amount = df.Amount
    Type = df.TypeName
    return Time, Name, Amount, Type 
    
"""建立数据库，并创建两个表，第一个是收入支出表，含有ID，项目时间，项目名称，项目金额，类型ID几列，第二个是项目类型表，含有项目ID和项目名称两列"""
def InitDatabase(name):
    con = db.CreateConnection("money.db")
    cu = db.CreateCursor(con)
    createIncome = "CREATE TABLE Income(ID INTEGER PRIMARY KEY, Time DATE, Name NVARCHAR, Amount DECIMAL(7,2), TypeID INTEGER);"
    createType = "CREATE TABLE IncomeType(TypeID INTEGER PRIMARY KEY, TypeName NVARCHAR);"
    res = db.RunQuery(cu, createIncome)
    res = db.RunQuery(cu, createType)
    con.close()
    
    
if __name__ == "__main__":
    """
    df = ReadDataform("moneyold.csv")
    print(df.columns)
    Time, Name, Amount, Type = ReadData(df)
    print(Time)
    print(Name)
    print(Amount)
    print(Type)
    """
    # InitDatabase("money.db")
    con = db.CreateConnection("money.db")
    cu = db.CreateCursor(con)
    query = "select name from sqlite_master where type='table'"
    res = db.RunQuery(cu, query)
    print(res)
    query = "PRAGMA table_info(Income)"
    res = db.RunQuery(cu, query)
    print(res)
    query = "PRAGMA table_info(IncomeType)"
    res = db.RunQuery(cu, query)
    print(res)
    con.close()
    